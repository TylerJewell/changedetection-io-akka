package io.akka.changedetection.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.ProcessorExceptions;
import io.akka.changedetection.text.PythonJson;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A page read by a real browser.
 *
 * <p>This is what a watch needs when the content only exists after the page's own script has
 * run, and it is also what supplies three things the plain fetcher cannot: a picture of the
 * page, the element geometry the visual selector draws over, and the stock reading the restock
 * processor falls back on. Those three are produced by running the original's own scripts
 * inside the page, so what they measure is what the original measures.
 */
public final class BrowserFetcher implements Fetcher {

  private final String name;
  private final String defaultConnectionUrl;


  public BrowserFetcher(String name, String defaultConnectionUrl) {
    this.name = name;
    this.defaultConnectionUrl = defaultConnectionUrl;
  }

  public static BrowserFetcher playwright() {
    return new BrowserFetcher("html_webdriver", driverUrl());
  }

  private static String driverUrl() {
    String url = System.getenv("PLAYWRIGHT_DRIVER_URL");
    if (url == null || url.isBlank()) {
      url = System.getenv("WEBDRIVER_URL");
    }
    if (url == null || url.isBlank()) {
      url = "ws://playwright-chrome:3000";
    }
    return url.replace("\"", "").strip();
  }

  /** Where this fetcher connects when a watch does not name somewhere else. */
  public String connectionUrl() {
    return defaultConnectionUrl;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return "Chrome/Javascript via \"" + defaultConnectionUrl + "\"";
  }

  @Override
  public boolean supportsScreenshots() {
    return true;
  }

  @Override
  public boolean supportsBrowserSteps() {
    return true;
  }

  @Override
  public Fetched fetch(Request request) {
    boolean allowFile = Fields.truthy(System.getenv("ALLOW_FILE_URI"));
    boolean allowRestricted = Fields.truthy(System.getenv("ALLOW_IANA_RESTRICTED_ADDRESSES"));
    UrlSafety.Verdict verdict = UrlSafety.isFetchAllowed(request.url, allowFile, allowRestricted);
    if (!verdict.allowed()) {
      throw new ProcessorExceptions.PageUnloadable(verdict.reason(), 0);
    }

    String connection =
        request.browserConnectionUrl == null || request.browserConnectionUrl.isBlank()
            ? defaultConnectionUrl
            : request.browserConnectionUrl;
    if (request.proxy != null && !request.proxy.isBlank()) {
      connection += (connection.contains("?") ? "&" : "?")
          + "--proxy-server=" + request.proxy;
    }

    long timeoutMillis = Math.max(1, request.timeoutSeconds) * 1000L;
    Fetched fetched = new Fetched();
    fetched.backendName = name;

    try (CdpClient client = CdpClient.connect(connection, timeoutMillis)) {
      int[] status = new int[] {200};
      Map<String, String> responseHeaders = new LinkedHashMap<>();

      JsonNode target =
          client.send("Target.createTarget", Map.of("url", "about:blank"));
      String targetId = target.path("targetId").asText();
      JsonNode attached =
          client.send(
              "Target.attachToTarget", Map.of("targetId", targetId, "flatten", true));
      String sessionId = attached.path("sessionId").asText();

      try {
        client.onEvent(
            (method, params) -> {
              if (method.equals("Network.responseReceived")
                  && params.path("type").asText("").equals("Document")) {
                status[0] = params.path("response").path("status").asInt(200);
                JsonNode headers = params.path("response").path("headers");
                headers
                    .fields()
                    .forEachRemaining(
                        entry -> responseHeaders.put(entry.getKey(), entry.getValue().asText()));
              }
            });

        client.send("Network.enable", Map.of(), sessionId);
        client.send("Page.enable", Map.of(), sessionId);
        client.send("Runtime.enable", Map.of(), sessionId);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("width", envInt("SCREENSHOT_DEFAULT_WIDTH", 1280));
        metrics.put("height", envInt("SCREENSHOT_DEFAULT_HEIGHT", 1024));
        metrics.put("deviceScaleFactor", 1);
        metrics.put("mobile", false);
        client.send("Emulation.setDeviceMetricsOverride", metrics, sessionId);

        if (!request.headers.isEmpty()) {
          client.send(
              "Network.setExtraHTTPHeaders", Map.of("headers", request.headers), sessionId);
        }

        String url = request.url.replaceFirst("(?i)^source:", "");
        client.send("Page.navigate", Map.of("url", url), sessionId);
        BrowserSteps.waitForLoad(client, sessionId);

        if (request.waitSeconds != null && request.waitSeconds > 0) {
          BrowserSteps.sleep(request.waitSeconds * 1000L);
        }

        List<Map<String, Object>> steps = BrowserSteps.validSteps(request.browserSteps);
        for (int index = 0; index < steps.size(); index++) {
          BrowserSteps.run(client, sessionId, steps.get(index), request.url, index + 1);
        }

        if (request.javascriptToRun != null && !request.javascriptToRun.isBlank()) {
          JsonNode result =
              BrowserSteps.evaluate(client, sessionId, request.javascriptToRun);
          if (result.has("exceptionDetails")) {
            throw new ProcessorExceptions.JsActionException(
                result.path("exceptionDetails").path("text").asText("script failed"), status[0]);
          }
        }

        JsonNode content =
            BrowserSteps.evaluate(client, sessionId, "document.documentElement.outerHTML");
        fetched.content = content.path("result").path("value").asText("");
        fetched.rawContent = fetched.content.getBytes(StandardCharsets.UTF_8);
        fetched.statusCode = status[0];
        fetched.headers.putAll(responseHeaders);
        if (fetched.header("content-type") == null) {
          fetched.headers.put("content-type", "text/html");
        }

        if (fetched.content.isEmpty() && !request.emptyPagesAreAChange) {
          throw new ProcessorExceptions.EmptyReply(status[0]);
        }

        boolean accepted =
            status[0] == 200
                || request.ignoreStatusCodes
                || request.acceptedStatusCodes.contains(status[0]);
        if (!accepted) {
          throw new ProcessorExceptions.NonSuccessStatus(status[0], fetched.content);
        }

        fetched.screenshot = screenshot(client, sessionId, request.screenshotFormat);

        String filtersJson =
            request.includeFilters.isEmpty()
                ? "''"
                : PythonJson.dumpsCompact(
                    PythonJson.MAPPER.valueToTree(request.includeFilters));
        BrowserSteps.evaluate(client, sessionId, "var include_filters=" + filtersJson + ";");
        JsonNode xpath =
            BrowserSteps.evaluate(client, sessionId, BrowserScripts.xpathElementScraper());
        fetched.xpathData = valueAsJson(xpath);

        JsonNode instock =
            BrowserSteps.evaluate(client, sessionId, BrowserScripts.stockNotInStock());
        if (instock.path("result").has("value")) {
          fetched.instockData = instock.path("result").path("value").asText(null);
        }

        if (request.fetchFavicon) {
          try {
            JsonNode favicon =
                BrowserSteps.evaluate(client, sessionId, BrowserScripts.faviconFetcher());
            JsonNode value = favicon.path("result").path("value");
            if (value.isObject() && value.has("base64")) {
              Map<String, String> blob = new LinkedHashMap<>();
              blob.put("base64", value.path("base64").asText());
              blob.put("url", value.path("url").asText(""));
              blob.put("mime_type", value.path("mime_type").asText(""));
              fetched.faviconBlob = blob;
            }
          } catch (RuntimeException e) {
            // A missing site icon is not a failed check.
          }
        }
      } finally {
        try {
          client.send("Target.closeTarget", Map.of("targetId", targetId));
        } catch (RuntimeException e) {
          // The browser may already have gone; the connection is closed either way.
        }
      }
    }
    return fetched;
  }

  public static byte[] screenshot(CdpClient client, String sessionId, String format) {
    Map<String, Object> params = new LinkedHashMap<>();
    String chosen = format == null || format.isBlank() ? "jpeg" : format.toLowerCase();
    params.put("format", chosen);
    if (chosen.equals("jpeg")) {
      params.put("quality", envInt("SCREENSHOT_QUALITY", 72));
    }
    params.put("captureBeyondViewport", true);
    try {
      JsonNode result = client.send("Page.captureScreenshot", params, sessionId);
      String data = result.path("data").asText("");
      return data.isEmpty() ? null : Base64.getDecoder().decode(data);
    } catch (RuntimeException e) {
      throw new ProcessorExceptions.ScreenshotUnavailable(200);
    }
  }

  public static String valueAsJson(JsonNode evaluated) {
    JsonNode value = evaluated.path("result").path("value");
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    return value.isTextual() ? value.textValue() : PythonJson.dumpsCompact(value);
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
