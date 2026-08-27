package io.akka.changedetection.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Every page the interface serves is served, from the shipped templates.
 *
 * <p>Driven over HTTP rather than by calling the renderer, because a page that renders but is
 * not routed, or is routed but refuses the request, is broken in a way only a request shows.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InterfacePagesIntegrationTest extends TestKitSupport {

  private static final Pattern WATCH_ROW = Pattern.compile("data-watch-uuid=\"([^\"]+)\"");

  private static String watchUuid;

  private StrictResponse<String> get(String path) {
    return httpClient
        .GET(path)
        .addHeader("Host", "localhost")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  private StrictResponse<String> postForm(String path, String encoded) {
    return httpClient
        .POST(path)
        .addHeader("Host", "localhost")
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .withRequestBody(
            akka.http.javadsl.model.ContentTypes.parse("application/x-www-form-urlencoded"),
            encoded.getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  @Test
  @Order(1)
  void theListPageIsServed() {
    var response = get("/");
    assertEquals(200, response.status().intValue(), response.body());
    assertTrue(response.body().contains("<title>Change Detection"), "the shipped shell");
  }

  @Test
  @Order(2)
  void aWatchCanBeAddedFromTheListPage() {
    var response =
        postForm(
            "/form/add/quickwatch",
            "url=https%3A%2F%2Fexample.com%2Fpage&tags=&processor=text_json_diff"
                + "&watch_submit_button=Watch");
    assertEquals(302, response.status().intValue(), response.body());

    // The list is a projection of the watches, so it catches up a moment after the write.
    String body = "";
    for (int attempt = 0; attempt < 40 && watchUuid == null; attempt++) {
      body = get("/").body();
      Matcher found = WATCH_ROW.matcher(body);
      if (found.find()) {
        watchUuid = found.group(1);
        break;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertNotNull(watchUuid, "the new watch has a row: " + body);
  }

  @Test
  @Order(3)
  void theEditPageIsServed() {
    var response = get("/edit/" + watchUuid);
    assertEquals(200, response.status().intValue(), response.body());
    assertTrue(response.body().contains("https://example.com/page"), "the watch's own address");
  }

  @Test
  @Order(4)
  void theUuidFeedListsTheWatch() {
    var response = get("/uuids");
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().contains(watchUuid), response.body());
  }

  @Test
  @Order(5)
  void theHistoryClearingPageIsServed() {
    var response = get("/clear_history");
    assertEquals(200, response.status().intValue(), response.body());
  }

  @Test
  @Order(6)
  void aShippedFileIsServed() {
    var response = get("/static/js/realtime.js");
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().length() > 100, "the shipped script");
  }
}
