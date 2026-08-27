package io.akka.changedetection.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The main list is served, and it is the shipped markup that comes back. */
class WatchListPageIntegrationTest extends TestKitSupport {

  private StrictResponse<String> get(String path) {
    return httpClient
        .GET(path)
        .addHeader("Host", "localhost")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  @Test
  void theListPageRenders() {
    var response = get("/");
    assertEquals(200, response.status().intValue(), response.body());
    assertTrue(response.body().contains("<title>Change Detection"), response.body());
  }
}
