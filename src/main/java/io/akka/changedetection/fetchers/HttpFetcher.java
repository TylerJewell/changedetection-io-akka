package io.akka.changedetection.fetchers;

import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.ProcessorExceptions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plain fetcher: one request, no browser.
 *
 * <p>Redirects are followed one at a time rather than by the client, because each hop has to be
 * checked against the address rules again. A page on a public host that redirects to an address
 * on the machine's own network is otherwise a way to make the server fetch that address, and
 * the check at the start would have passed.
 *
 * <p>Which character set the body is in is decided in the order a browser decides it: the
 * server's declaration, then a byte-order mark, then the document's own declaration, then a
 * statistical guess. Getting it wrong does not fail the check -- it silently compares mojibake,
 * and every check after it compares different mojibake.
 */
public final class HttpFetcher implements Fetcher {

  private static final Pattern XML_ENCODING =
      Pattern.compile("<\\?xml[^>]+encoding=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
  private static final Pattern META_CHARSET =
      Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?\\s*([^\"'\\s;>]+)", Pattern.CASE_INSENSITIVE);

  private static final int MAX_REDIRECTS = 10;

  @Override
  public String name() {
    return "html_requests";
  }

  @Override
  public String description() {
    return "Basic fast Plaintext/HTTP Client";
  }

  @Override
  public boolean supportsScreenshots() {
    return false;
  }

  @Override
  public boolean supportsBrowserSteps() {
    return false;
  }

  @Override
  public Fetched fetch(Request request) {
    if (!request.browserSteps.isEmpty()) {
      throw new ProcessorExceptions.BrowserStepsInUnsupportedFetcher();
    }

    boolean allowFile = Fields.truthy(System.getenv("ALLOW_FILE_URI"));
    boolean allowRestricted = Fields.truthy(System.getenv("ALLOW_IANA_RESTRICTED_ADDRESSES"));

    UrlSafety.Verdict verdict = UrlSafety.isFetchAllowed(request.url, allowFile, allowRestricted);
    if (!verdict.allowed()) {
      throw new ProcessorExceptions.PageUnloadable(verdict.reason(), 0);
    }

    if (request.url.toLowerCase(Locale.ROOT).startsWith("file://")) {
      return readFile(request);
    }

    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(request.timeoutSeconds));
    if (request.proxy != null && !request.proxy.isBlank()) {
      try {
        URI proxyUri = URI.create(request.proxy);
        int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : 8080;
        builder.proxy(ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), port)));
      } catch (RuntimeException e) {
        throw new ProcessorExceptions.PageUnloadable(
            "Proxy connection failed? " + e.getMessage(), 0);
      }
    }
    HttpClient client = builder.build();

    String currentUrl = request.url;
    HttpResponse<byte[]> response;
    int hops = 0;
    while (true) {
      response = send(client, request, currentUrl, hops == 0);
      int status = response.statusCode();
      if (status < 300 || status > 399) {
        break;
      }
      if (++hops > MAX_REDIRECTS) {
        throw new ProcessorExceptions.PageUnloadable("Too many redirects", status);
      }
      String location = response.headers().firstValue("location").orElse("");
      if (location.isEmpty()) {
        break;
      }
      String next = URI.create(currentUrl).resolve(location).toString();
      if (!allowRestricted) {
        UrlSafety.Verdict hop = UrlSafety.isFetchAllowed(next, allowFile, false);
        if (!hop.allowed()) {
          throw new ProcessorExceptions.PageUnloadable(
              "Redirect blocked: '" + next + "' " + hop.reason(), status);
        }
      }
      currentUrl = next;
    }

    Fetched fetched = new Fetched();
    fetched.backendName = name();
    fetched.statusCode = response.statusCode();
    for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
      fetched.headers.put(entry.getKey(), String.join(", ", entry.getValue()));
    }
    byte[] body = response.body();
    fetched.rawContent = body;

    if (body.length == 0) {
      if (!request.emptyPagesAreAChange) {
        throw new ProcessorExceptions.EmptyReply(response.statusCode());
      }
    }

    boolean accepted =
        response.statusCode() == 200
            || request.ignoreStatusCodes
            || request.acceptedStatusCodes.contains(response.statusCode());
    if (!accepted) {
      throw new ProcessorExceptions.NonSuccessStatus(
          response.statusCode(), new String(body, StandardCharsets.UTF_8));
    }

    if (request.isBinary) {
      fetched.content = io.akka.changedetection.text.PythonText.md5Hex(body);
    } else {
      fetched.content = decode(body, fetched.header("content-type"));
    }

    String contentType = fetched.contentType();
    if (contentType != null && contentType.contains("image/")) {
      // A watch on an image compares the picture rather than its text, so the body is handed
      // on as one.
      fetched.screenshot = body;
    }

    return fetched;
  }

  private HttpResponse<byte[]> send(
      HttpClient client, Request request, String url, boolean withBody) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(request.timeoutSeconds));
    for (Map.Entry<String, String> header : request.headers.entrySet()) {
      if (isSettableHeader(header.getKey())) {
        builder.header(header.getKey(), header.getValue());
      }
    }
    String method = withBody ? request.method : "GET";
    byte[] body =
        withBody && request.body != null ? request.body.getBytes(StandardCharsets.UTF_8) : null;
    if (body != null && !method.equalsIgnoreCase("GET")) {
      builder.method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.ofByteArray(body));
    } else {
      builder.method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.noBody());
    }

    // The retry is for a connection that failed rather than for a page that answered: an
    // answer of any kind, including an error, is the check's result and is not retried.
    int attempts = maxRetries();
    IOException lastFailure = null;
    for (int attempt = 0; attempt <= attempts; attempt++) {
      try {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      } catch (IOException e) {
        lastFailure = e;
        try {
          Thread.sleep((long) (500 * Math.pow(2, attempt)));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ProcessorExceptions.PageUnloadable("interrupted", 0);
      }
    }
    throw new ProcessorExceptions.PageUnloadable(
        lastFailure == null ? "request failed" : String.valueOf(lastFailure.getMessage()), 0);
  }

  private static int maxRetries() {
    String value = System.getenv("REQUESTS_RETRY_MAX_COUNT");
    if (value == null || value.isBlank()) {
      return 6;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return 6;
    }
  }

  /** Headers the client refuses to let a caller set. */
  private static boolean isSettableHeader(String name) {
    String lowered = name.toLowerCase(Locale.ROOT);
    return !List.of("connection", "content-length", "expect", "host", "upgrade")
        .contains(lowered);
  }

  private Fetched readFile(Request request) {
    try {
      Path path = Path.of(URI.create(request.url));
      byte[] body = Files.readAllBytes(path);
      Fetched fetched = new Fetched();
      fetched.backendName = name();
      fetched.statusCode = 200;
      fetched.rawContent = body;
      fetched.content = new String(body, StandardCharsets.UTF_8);
      fetched.headers.put("content-type", "text/html");
      return fetched;
    } catch (Exception e) {
      throw new ProcessorExceptions.PageUnloadable(String.valueOf(e.getMessage()), 0);
    }
  }

  /**
   * The body read as text, in the order a browser decides the character set.
   *
   * <p>Feeds are handled separately from pages because a feed declares its own encoding in its
   * first line and a statistical guess routinely reads a correct one as something else.
   */
  static String decode(byte[] body, String contentTypeHeader) {
    String header = contentTypeHeader == null ? "" : contentTypeHeader.toLowerCase(Locale.ROOT);
    Charset declared = charsetFromHeader(header);
    if (declared != null) {
      return new String(body, declared);
    }

    if (header.contains("xml") || header.contains("rss")) {
      String head = new String(body, 0, Math.min(body.length, 200), StandardCharsets.ISO_8859_1);
      Matcher matcher = XML_ENCODING.matcher(head);
      if (matcher.find()) {
        Charset charset = charsetOrNull(matcher.group(1));
        if (charset != null) {
          return new String(body, charset);
        }
      }
      return new String(body, StandardCharsets.UTF_8);
    }

    if (body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB
        && (body[2] & 0xFF) == 0xBF) {
      return new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
    }
    if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xFE) {
      return new String(body, 2, body.length - 2, StandardCharsets.UTF_16LE);
    }
    if (body.length >= 2 && (body[0] & 0xFF) == 0xFE && (body[1] & 0xFF) == 0xFF) {
      return new String(body, 2, body.length - 2, StandardCharsets.UTF_16BE);
    }

    String head = new String(body, 0, Math.min(body.length, 2000), StandardCharsets.ISO_8859_1);
    Matcher meta = META_CHARSET.matcher(head);
    if (meta.find()) {
      Charset charset = charsetOrNull(meta.group(1));
      if (charset != null) {
        return new String(body, charset);
      }
    }

    Charset guessed = CharsetGuess.detect(body);
    return new String(body, guessed == null ? StandardCharsets.UTF_8 : guessed);
  }

  private static Charset charsetFromHeader(String header) {
    int at = header.indexOf("charset=");
    if (at < 0) {
      return null;
    }
    String value = header.substring(at + "charset=".length()).split("[;\\s]")[0]
        .replace("\"", "").replace("'", "").strip();
    return charsetOrNull(value);
  }

  private static Charset charsetOrNull(String name) {
    try {
      return Charset.forName(name.strip());
    } catch (Exception e) {
      return null;
    }
  }

  /** The headers a fetch sends, with the operator's own merged over the defaults. */
  public static Map<String, String> mergeHeaders(
      Map<String, String> defaults, Map<String, String> overrides) {
    Map<String, String> merged = new LinkedHashMap<>(defaults);
    List<String> replaced = new ArrayList<>();
    for (Map.Entry<String, String> entry : overrides.entrySet()) {
      for (String existing : merged.keySet()) {
        if (existing.equalsIgnoreCase(entry.getKey())) {
          replaced.add(existing);
        }
      }
      for (String key : replaced) {
        merged.remove(key);
      }
      replaced.clear();
      merged.put(entry.getKey(), entry.getValue());
    }
    return merged;
  }
}
