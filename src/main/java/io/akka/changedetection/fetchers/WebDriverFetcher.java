package io.akka.changedetection.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.ProcessorExceptions;
import io.akka.changedetection.text.PythonJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A page read by a browser reached through the remote-control protocol.
 *
 * <p>The other browser fetcher speaks the browser's own protocol; this one speaks the
 * standardised one that a grid or a hosted browser service answers. Which is available depends
 * on the deployment, and the original ships both for the same reason.
 *
 * <p>It reports success for any page that loaded, because the protocol does not carry the
 * page's status code -- the original notes the same gap.
 */
public final class WebDriverFetcher implements Fetcher {

  private final String endpoint;

  public WebDriverFetcher() {
    String url = System.getenv("WEBDRIVER_URL");
    this.endpoint =
        (url == null || url.isBlank() ? "http://browser-chrome:4444/wd/hub" : url)
            .replace("\"", "")
            .strip();
  }

  @Override
  public String name() {
    return "html_webdriver_selenium";
  }

  @Override
  public String description() {
    return "WebDriver Chrome/Javascript via \"" + endpoint + "\"";
  }

  @Override
  public boolean supportsScreenshots() {
    return true;
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

    HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(90)).build();
    String sessionId = null;
    try {
      List<String> arguments = new ArrayList<>();
      String options = System.getenv("CHROME_OPTIONS");
      if (options != null) {
        for (String line : options.strip().split("\\R")) {
          if (!line.strip().isEmpty()) {
            arguments.add(line.strip());
          }
        }
      }
      if (request.proxy != null && !request.proxy.isBlank()) {
        arguments.add("--proxy-server=" + request.proxy);
      }
      boolean hasWindowSize = arguments.stream().anyMatch(a -> a.startsWith("--window-size"));
      if (!hasWindowSize) {
        arguments.add("--window-size=1280,1024");
      }

      Map<String, Object> chromeOptions = new LinkedHashMap<>();
      chromeOptions.put("args", arguments);
      Map<String, Object> alwaysMatch = new LinkedHashMap<>();
      alwaysMatch.put("browserName", "chrome");
      alwaysMatch.put("goog:chromeOptions", chromeOptions);
      Map<String, Object> capabilities = new LinkedHashMap<>();
      capabilities.put("alwaysMatch", alwaysMatch);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("capabilities", capabilities);

      JsonNode created = post(client, endpoint + "/session", body);
      sessionId = created.path("value").path("sessionId").asText(
          created.path("sessionId").asText(""));
      if (sessionId.isEmpty()) {
        throw new ProcessorExceptions.BrowserConnectError(
            "The remote browser did not open a session");
      }

      String base = endpoint + "/session/" + sessionId;
      post(client, base + "/url", Map.of("url", request.url.replaceFirst("(?i)^source:", "")));

      int delay = envInt("WEBDRIVER_DELAY_BEFORE_CONTENT_READY", 5);
      BrowserSteps.sleep((delay + (request.waitSeconds == null ? 0 : request.waitSeconds)) * 1000L);

      if (request.javascriptToRun != null && !request.javascriptToRun.isBlank()) {
        post(client, base + "/execute/sync",
            Map.of("script", request.javascriptToRun, "args", List.of()));
        BrowserSteps.sleep(delay * 1000L);
      }

      JsonNode source = get(client, base + "/source");
      Fetched fetched = new Fetched();
      fetched.backendName = name();
      fetched.content = source.path("value").asText("");
      fetched.rawContent = fetched.content.getBytes(StandardCharsets.UTF_8);
      fetched.statusCode = 200;
      fetched.headers.put("content-type", "text/html");

      JsonNode screenshot = get(client, base + "/screenshot");
      String data = screenshot.path("value").asText("");
      if (!data.isEmpty()) {
        fetched.screenshot = Base64.getDecoder().decode(data);
      }

      if (fetched.content.isEmpty() && !request.emptyPagesAreAChange) {
        throw new ProcessorExceptions.EmptyReply(200);
      }
      return fetched;
    } finally {
      if (sessionId != null) {
        try {
          delete(client, endpoint + "/session/" + sessionId);
        } catch (RuntimeException e) {
          // A session that will not close is the remote browser's problem; leaving it open
          // would block the next check, so the failure is not propagated over a real result.
        }
      }
    }
  }

  private static JsonNode post(HttpClient client, String url, Map<String, Object> body) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(envInt("WEBDRIVER_PAGELOAD_TIMEOUT", 45) + 30))
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      PythonJson.MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      return PythonJson.MAPPER.readTree(response.body());
    } catch (Exception e) {
      throw new ProcessorExceptions.BrowserConnectError(
          "Error while trying to reach the remote browser: " + e.getMessage());
    }
  }

  private static JsonNode get(HttpClient client, String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      return PythonJson.MAPPER.readTree(response.body());
    } catch (Exception e) {
      throw new ProcessorExceptions.BrowserConnectError(
          "Error while reading from the remote browser: " + e.getMessage());
    }
  }

  private static void delete(HttpClient client, String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).DELETE().build();
      client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new ProcessorExceptions.BrowserConnectError(String.valueOf(e.getMessage()));
    }
  }

  private static int envInt(String name, int fallback) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
