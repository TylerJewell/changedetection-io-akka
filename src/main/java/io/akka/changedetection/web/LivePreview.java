package io.akka.changedetection.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.fetchers.BrowserFetcher;
import io.akka.changedetection.fetchers.BrowserScripts;
import io.akka.changedetection.fetchers.BrowserSteps;
import io.akka.changedetection.fetchers.CdpClient;
import java.util.Map;
import java.util.UUID;

/**
 * One look at a page the operator has not saved yet.
 *
 * <p>The browser is opened, the page loaded, a picture and the element map taken, and the
 * browser closed again -- there is no session to keep, because the operator is choosing an
 * element on a page they may never watch.
 *
 * <p>What comes back is parked under a temporary identifier so that saving the watch afterwards
 * does not fetch the page a second time. Nothing sweeps that store on a timer; it is cleared
 * when the watch is saved, and overwritten by the next look at the same page.
 */
final class LivePreview {

  private LivePreview() {}

  static UiExtrasEndpoint.Snapshot capture(Store store, String url) {
    String connection = BrowserStepsEndpoint.browserConnectionUrl(store);
    try (CdpClient client = CdpClient.connect(connection, 30_000)) {
      JsonNode target = client.send("Target.createTarget", Map.of("url", "about:blank"));
      String targetId = target.path("targetId").asText();
      JsonNode attached =
          client.send("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true));
      String sessionId = attached.path("sessionId").asText();
      client.send("Network.enable", Map.of(), sessionId);
      client.send("Page.enable", Map.of(), sessionId);
      client.send("Runtime.enable", Map.of(), sessionId);

      client.send("Page.navigate", Map.of("url", url), sessionId);
      BrowserSteps.waitForLoad(client, sessionId);

      byte[] screenshot = BrowserFetcher.screenshot(client, sessionId, "jpeg");
      if (screenshot == null || screenshot.length == 0) {
        throw new IllegalStateException("Could not capture a screenshot for that URL");
      }
      BrowserSteps.evaluate(client, sessionId, "var include_filters='';");
      JsonNode xpath =
          BrowserSteps.evaluate(client, sessionId, BrowserScripts.xpathElementScraper());
      String xpathData = BrowserFetcher.valueAsJson(xpath);
      JsonNode content =
          BrowserSteps.evaluate(client, sessionId, "document.documentElement.outerHTML");
      String html = content.path("result").path("value").asText("");

      String temporary = UUID.randomUUID().toString();
      store.saveSideStore(
          temporary, "screenshot", java.util.Base64.getEncoder().encodeToString(screenshot));
      store.saveSideStore(temporary, "elements", xpathData);
      if (!html.isEmpty()) {
        store.saveSideStore(temporary, "preload-fetch", html);
      }
      return new UiExtrasEndpoint.Snapshot(temporary, screenshot, xpathData, html);
    }
  }
}
