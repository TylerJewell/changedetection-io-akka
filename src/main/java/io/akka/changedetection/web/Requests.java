package io.akka.changedetection.web;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.http.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reading a request and shaping a reply, in the terms the rest of the interface uses. */
public final class Requests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Requests() {}

  /** One uploaded file: what it was called and what it held. */
  public record Upload(String field, String filename, byte[] content) {}

  /** A submission: its named values, and anything uploaded with it. */
  public record Submission(Map<String, List<String>> values, List<Upload> uploads) {
    public String first(String name) {
      List<String> found = values.get(name);
      return found == null || found.isEmpty() ? "" : found.get(0);
    }

    public boolean has(String name) {
      return values.containsKey(name);
    }

    public Upload upload(String field) {
      for (Upload upload : uploads) {
        if (upload.field().equals(field)) {
          return upload;
        }
      }
      return null;
    }
  }

  public static String header(RequestContext context, String name) {
    return context.requestHeader(name).map(h -> h.value()).orElse("");
  }

  public static String cookie(RequestContext context, String name) {
    String header = header(context, "Cookie");
    if (header.isEmpty()) {
      return "";
    }
    for (String pair : header.split(";")) {
      int equals = pair.indexOf('=');
      if (equals < 0) {
        continue;
      }
      if (pair.substring(0, equals).trim().equals(name)) {
        return pair.substring(equals + 1).trim();
      }
    }
    return "";
  }

  /**
   * Every value of every query argument.
   *
   * <p>Every value, not the first: several of the interface's own controls repeat a name -- the
   * list of watches a bulk operation applies to -- and only the whole list means anything.
   */
  public static Map<String, List<String>> query(RequestContext context) {
    return new LinkedHashMap<>(context.queryParams().toMultiMap());
  }

  public static String queryValue(RequestContext context, String name, String fallback) {
    List<String> found = query(context).get(name);
    return found == null || found.isEmpty() ? fallback : found.get(0);
  }

  /** What a submission carried, read according to how it said it was encoded. */
  public static Submission submission(RequestContext context, HttpEntity.Strict body) {
    String contentType = header(context, "Content-Type");
    if (contentType.isEmpty()) {
      contentType = body.getContentType().toString();
    }
    byte[] bytes = body.getData().toArray();
    if (contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
      return multipart(bytes, boundaryOf(contentType));
    }
    return new Submission(
        parseEncoded(new String(bytes, StandardCharsets.UTF_8)), new ArrayList<>());
  }

  public static Map<String, Object> json(HttpEntity.Strict body) {
    byte[] bytes = body.getData().toArray();
    if (bytes.length == 0) {
      return new LinkedHashMap<>();
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = MAPPER.readValue(bytes, Map.class);
      return parsed == null ? new LinkedHashMap<>() : parsed;
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  static Map<String, List<String>> parseEncoded(String encoded) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    if (encoded == null || encoded.isEmpty()) {
      return values;
    }
    for (String pair : encoded.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      String name = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      values
          .computeIfAbsent(decode(name), key -> new ArrayList<>())
          .add(decode(value));
    }
    return values;
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      // A value that will not decode is taken as written, which is what a form library does
      // rather than refusing the whole submission.
      return value;
    }
  }

  static String boundaryOf(String contentType) {
    for (String part : contentType.split(";")) {
      String trimmed = part.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
        String value = trimmed.substring("boundary=".length()).trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
          value = value.substring(1, value.length() - 1);
        }
        return value;
      }
    }
    return "";
  }

  /**
   * A multipart submission, split on its own boundary.
   *
   * <p>Parsed over the bytes rather than over a decoded string: one of the parts is an uploaded
   * file, and decoding a spreadsheet or an archive as text would destroy it.
   */
  static Submission multipart(byte[] bytes, String boundary) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    List<Upload> uploads = new ArrayList<>();
    if (boundary.isEmpty()) {
      return new Submission(values, uploads);
    }
    byte[] separator = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
    List<int[]> parts = new ArrayList<>();
    int at = indexOf(bytes, separator, 0);
    while (at >= 0) {
      int from = at + separator.length;
      if (from + 2 <= bytes.length && bytes[from] == '-' && bytes[from + 1] == '-') {
        break;
      }
      if (from + 2 <= bytes.length && bytes[from] == '\r' && bytes[from + 1] == '\n') {
        from += 2;
      }
      int next = indexOf(bytes, separator, from);
      if (next < 0) {
        break;
      }
      int to = next;
      // The boundary that follows a part is preceded by the line break that ends it.
      if (to - 2 >= from && bytes[to - 2] == '\r' && bytes[to - 1] == '\n') {
        to -= 2;
      }
      parts.add(new int[] {from, to});
      at = next;
    }

    byte[] blankLine = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    for (int[] part : parts) {
      int headerEnd = indexOf(bytes, blankLine, part[0]);
      if (headerEnd < 0 || headerEnd > part[1]) {
        continue;
      }
      String headers =
          new String(bytes, part[0], headerEnd - part[0], StandardCharsets.UTF_8);
      int contentFrom = headerEnd + blankLine.length;
      byte[] content = new byte[Math.max(0, part[1] - contentFrom)];
      System.arraycopy(bytes, contentFrom, content, 0, content.length);

      String name = parameterOf(headers, "name");
      String filename = parameterOf(headers, "filename");
      if (name.isEmpty()) {
        continue;
      }
      if (!filename.isEmpty()) {
        uploads.add(new Upload(name, filename, content));
        continue;
      }
      values
          .computeIfAbsent(name, key -> new ArrayList<>())
          .add(new String(content, StandardCharsets.UTF_8));
    }
    return new Submission(values, uploads);
  }

  private static String parameterOf(String headers, String parameter) {
    String needle = parameter + "=\"";
    int at = headers.indexOf(needle);
    if (at < 0) {
      return "";
    }
    int from = at + needle.length();
    int to = headers.indexOf('"', from);
    return to < 0 ? "" : headers.substring(from, to);
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int start = Math.max(0, from); start + needle.length <= haystack.length; start++) {
      for (int offset = 0; offset < needle.length; offset++) {
        if (haystack[start + offset] != needle[offset]) {
          continue outer;
        }
      }
      return start;
    }
    return -1;
  }

  // ------------------------------------------------------------------ replies

  /** A document already written as text, put back as values so it nests inside an answer. */
  public static Object parseJson(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    try {
      return MAPPER.readValue(text, Object.class);
    } catch (Exception e) {
      return text;
    }
  }

  public static HttpResponse html(String markup) {
    return html(StatusCodes.OK, markup);
  }

  public static HttpResponse html(StatusCode status, String markup) {
    return HttpResponse.create()
        .withStatus(status)
        .withEntity(ContentTypes.TEXT_HTML_UTF8, markup.getBytes(StandardCharsets.UTF_8));
  }

  public static HttpResponse text(String body) {
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, body.getBytes(StandardCharsets.UTF_8));
  }

  public static HttpResponse text(StatusCode status, String body) {
    return HttpResponse.create()
        .withStatus(status)
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, body.getBytes(StandardCharsets.UTF_8));
  }

  public static HttpResponse json(Object value) {
    return json(StatusCodes.OK, value);
  }

  public static HttpResponse json(StatusCode status, Object value) {
    byte[] body;
    try {
      body = MAPPER.writeValueAsBytes(value);
    } catch (Exception e) {
      body = "{}".getBytes(StandardCharsets.UTF_8);
    }
    return HttpResponse.create()
        .withStatus(status)
        .withEntity(ContentTypes.APPLICATION_JSON, body);
  }

  public static HttpResponse bytes(StatusCode status, ContentType type, byte[] body) {
    return HttpResponse.create().withStatus(status).withEntity(type, body);
  }

  public static HttpResponse download(String filename, ContentType type, byte[] body) {
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .addHeader(
            RawHeader.create(
                "Content-Disposition", "attachment; filename=\"" + filename + "\""))
        .withEntity(type, body);
  }

  /** The reply that sends the browser somewhere else, as a form submission does. */
  public static HttpResponse redirect(String path) {
    return HttpResponse.create()
        .withStatus(StatusCodes.FOUND)
        .addHeader(Location.create(path))
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, new byte[0]);
  }

  public static HttpResponse notFound() {
    return text(StatusCodes.NOT_FOUND, "Not found");
  }

  public static ContentType typeFor(String filename) {
    String lower = filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".css")) {
      return ContentTypes.create(MediaTypes.TEXT_CSS, akka.http.javadsl.model.HttpCharsets.UTF_8);
    }
    if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
      return ContentTypes.create(
          MediaTypes.APPLICATION_JAVASCRIPT, akka.http.javadsl.model.HttpCharsets.UTF_8);
    }
    if (lower.endsWith(".json") || lower.endsWith(".map")) {
      return ContentTypes.APPLICATION_JSON;
    }
    if (lower.endsWith(".svg")) {
      // A picture, not a document: served under anything but an image type, a browser
      // refuses to draw it and every icon on the page becomes a broken-image placeholder.
      return ContentTypes.parse("image/svg+xml; charset=utf-8");
    }
    if (lower.endsWith(".png")) {
      return ContentTypes.create(MediaTypes.IMAGE_PNG);
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return ContentTypes.create(MediaTypes.IMAGE_JPEG);
    }
    if (lower.endsWith(".gif")) {
      return ContentTypes.create(MediaTypes.IMAGE_GIF);
    }
    if (lower.endsWith(".ico")) {
      return ContentTypes.create(MediaTypes.applicationBinary("vnd.microsoft.icon", false));
    }
    if (lower.endsWith(".woff2")) {
      return ContentTypes.create(MediaTypes.applicationBinary("font-woff2", false));
    }
    if (lower.endsWith(".woff")) {
      return ContentTypes.create(MediaTypes.applicationBinary("font-woff", false));
    }
    if (lower.endsWith(".html")) {
      return ContentTypes.TEXT_HTML_UTF8;
    }
    if (lower.endsWith(".txt")) {
      return ContentTypes.TEXT_PLAIN_UTF8;
    }
    if (lower.endsWith(".xml")) {
      return ContentTypes.TEXT_XML_UTF8;
    }
    return ContentTypes.APPLICATION_OCTET_STREAM;
  }
}
