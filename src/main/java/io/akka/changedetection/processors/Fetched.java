package io.akka.changedetection.processors;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** What a fetch produced, whatever fetched it. */
public final class Fetched {

  public String content = "";
  public byte[] rawContent = new byte[0];
  public Map<String, String> headers = new LinkedHashMap<>();
  public int statusCode = 200;
  public byte[] screenshot;
  public String xpathData;
  public String instockData;
  public String backendName = "html_requests";
  public Map<String, String> faviconBlob;

  public Fetched() {}

  public Fetched(String content, String contentType, int statusCode) {
    this.content = content;
    this.rawContent = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
    this.headers.put("content-type", contentType);
    this.statusCode = statusCode;
  }

  /** A header, found without regard to the case the server used. */
  public String header(String name) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  public String contentType() {
    String value = header("content-type");
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  public void clearContent() {
    content = "";
    rawContent = new byte[0];
    screenshot = null;
    xpathData = null;
  }
}
