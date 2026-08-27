package io.akka.changedetection.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The programmatic interface, exercised over HTTP.
 *
 * <p>Driven through the routes rather than through the store, because everything this surface
 * decides -- which fields a caller may set, what a missing record answers, what a switch in the
 * query string does -- happens in the route and nowhere else. A test that called the store
 * would agree with itself and prove nothing about the interface.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiV1IntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String watchUuid;
  private static String tagUuid;

  private static String apiKey;

  /**
   * The key this installation generated for itself, read off the page that shows it.
   *
   * <p>Taken from the running service rather than set here, because whether the key is required
   * at all is a setting, and a test that supplied its own would be testing its own arrangement.
   */
  private String key() {
    if (apiKey == null) {
      var settings =
          httpClient
              .GET("/settings")
              .addHeader("Host", "localhost")
              .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
              .invoke();
      var found =
          java.util.regex.Pattern.compile("id=\"api-key\">([^<]*)<").matcher(settings.body());
      apiKey = found.find() ? found.group(1).strip() : "";
    }
    return apiKey;
  }

  private StrictResponse<String> get(String path) {
    return httpClient
        .GET(path)
        .addHeader("Host", "localhost")
        .addHeader("x-api-key", key())
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  private StrictResponse<String> send(String method, String path, String json) {
    var request =
        switch (method) {
          case "POST" -> httpClient.POST(path);
          case "PUT" -> httpClient.PUT(path);
          case "DELETE" -> httpClient.DELETE(path);
          default -> httpClient.GET(path);
        };
    var withBody =
        json == null
            ? request
            : request.withRequestBody(
                akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
                json.getBytes(StandardCharsets.UTF_8));
    return withBody
        .addHeader("Host", "localhost")
        .addHeader("x-api-key", key())
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  private static JsonNode parse(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (Exception e) {
      throw new AssertionError("not a readable answer: " + body, e);
    }
  }

  @Test
  @Order(1)
  void theDescriptionIsPublishedWithoutAKey() {
    var response = get("/api/v1/full-spec");
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().contains("openapi"), "the published description");
    assertTrue(response.body().contains("/api/v1/watch"), "and it names the routes");
  }

  @Test
  @Order(2)
  void aWatchCanBeCreated() {
    var response =
        send("POST", "/api/v1/watch", "{\"url\":\"https://example.com/api-created\"}");
    assertEquals(201, response.status().intValue(), response.body());
    watchUuid = parse(response.body()).get("uuid").asText();
    assertNotNull(watchUuid);
    assertFalse(watchUuid.isEmpty());
  }

  @Test
  @Order(3)
  void anAddressThatIsNotOneIsRefused() {
    var response = send("POST", "/api/v1/watch", "{\"url\":\"javascript:alert(1)\"}");
    assertEquals(400, response.status().intValue(), response.body());
    assertTrue(response.body().contains("Invalid or unsupported URL"));
  }

  @Test
  @Order(4)
  void aWatchThatOptsOutOfTheSharedIntervalMustNameOne() {
    var response =
        send(
            "POST",
            "/api/v1/watch",
            "{\"url\":\"https://example.com/no-interval\","
                + "\"time_between_check_use_default\":false,"
                + "\"time_between_check\":{\"weeks\":0,\"days\":0,\"hours\":0,\"minutes\":0,"
                + "\"seconds\":0}}");
    assertEquals(400, response.status().intValue(), response.body());
    assertTrue(response.body().contains("At least one time interval"));
  }

  @Test
  @Order(5)
  void oneWatchCanBeRead() {
    var response = get("/api/v1/watch/" + watchUuid);
    assertEquals(200, response.status().intValue(), response.body());
    JsonNode body = parse(response.body());
    assertEquals("https://example.com/api-created", body.get("url").asText());
    assertEquals(0, body.get("history_n").asInt(), "no check has run yet");
    assertTrue(body.has("link"), "the derived address is added to the stored fields");
    assertTrue(body.has("processor_config_restock_diff_source"));
  }

  @Test
  @Order(6)
  void anInternalFieldIsNeverPartOfTheAnswer() {
    var response = get("/api/v1/watch/" + watchUuid);
    JsonNode body = parse(response.body());
    body.fieldNames()
        .forEachRemaining(
            name -> assertFalse(name.startsWith("__"), name + " is the service's own"));
  }

  @Test
  @Order(7)
  void aWatchThatDoesNotExistAnswersNotFound() {
    var response = get("/api/v1/watch/00000000-0000-4000-8000-000000000000");
    assertEquals(404, response.status().intValue());
    assertTrue(response.body().contains("No watch exists with the UUID"));
  }

  @Test
  @Order(8)
  void aWatchCanBePausedAndUnpausedThroughTheQueryString() {
    assertEquals(200, get("/api/v1/watch/" + watchUuid + "?paused=paused").status().intValue());
    assertTrue(parse(get("/api/v1/watch/" + watchUuid).body()).get("paused").asBoolean());

    assertEquals(
        200, get("/api/v1/watch/" + watchUuid + "?paused=unpaused").status().intValue());
    assertFalse(parse(get("/api/v1/watch/" + watchUuid).body()).get("paused").asBoolean());
  }

  @Test
  @Order(9)
  void aWatchCanBeMutedAndUnmutedThroughTheQueryString() {
    assertEquals(200, get("/api/v1/watch/" + watchUuid + "?muted=muted").status().intValue());
    assertTrue(
        parse(get("/api/v1/watch/" + watchUuid).body()).get("notification_muted").asBoolean());

    assertEquals(200, get("/api/v1/watch/" + watchUuid + "?muted=unmuted").status().intValue());
    assertFalse(
        parse(get("/api/v1/watch/" + watchUuid).body()).get("notification_muted").asBoolean());
  }

  @Test
  @Order(10)
  void aWatchCanBeUpdated() {
    var response =
        send("PUT", "/api/v1/watch/" + watchUuid, "{\"title\":\"Renamed by the API\"}");
    assertEquals(200, response.status().intValue(), response.body());
    assertEquals(
        "Renamed by the API",
        parse(get("/api/v1/watch/" + watchUuid).body()).get("title").asText());
  }

  @Test
  @Order(11)
  void aFieldNobodyDeclaredIsRefusedRatherThanStored() {
    var response =
        send("PUT", "/api/v1/watch/" + watchUuid, "{\"not_a_real_field\":\"anything\"}");
    assertEquals(400, response.status().intValue(), response.body());
    assertTrue(response.body().contains("Unknown field(s): not_a_real_field"));
  }

  @Test
  @Order(12)
  void aFieldTheServiceOwnsIsIgnoredRatherThanRefused() {
    // A caller that reads a watch and writes it back sends these; refusing would make the
    // obvious round trip fail, and accepting them would let a caller rewrite the check history.
    var response =
        send(
            "PUT",
            "/api/v1/watch/" + watchUuid,
            "{\"last_checked\":999,\"history_n\":42,\"title\":\"Round tripped\"}");
    assertEquals(200, response.status().intValue(), response.body());
    JsonNode body = parse(get("/api/v1/watch/" + watchUuid).body());
    assertEquals("Round tripped", body.get("title").asText());
    assertEquals(0, body.get("history_n").asInt(), "the count is derived, not accepted");
  }

  @Test
  @Order(13)
  void theListNamesEveryWatch() {
    var response = get("/api/v1/watch");
    assertEquals(200, response.status().intValue());
    JsonNode body = parse(response.body());
    assertTrue(body.has(watchUuid));
    JsonNode row = body.get(watchUuid);
    for (String field :
        new String[] {
          "last_changed", "last_checked", "last_error", "link", "page_title", "tags", "title",
          "url", "viewed"
        }) {
      assertTrue(row.has(field), "the list row carries " + field);
    }
  }

  @Test
  @Order(14)
  void theHistoryOfAWatchWithNoChecksIsEmpty() {
    var response = get("/api/v1/watch/" + watchUuid + "/history");
    assertEquals(200, response.status().intValue());
    assertEquals(0, parse(response.body()).size());
  }

  @Test
  @Order(15)
  void aSnapshotOfAWatchWithNoHistoryIsNotFound() {
    var response = get("/api/v1/watch/" + watchUuid + "/history/latest");
    assertEquals(404, response.status().intValue());
    assertTrue(response.body().contains("no history exists"));
  }

  @Test
  @Order(16)
  void searchingNeedsSomethingToSearchFor() {
    var response = get("/api/v1/search");
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("Search query 'q' parameter is required"));
  }

  @Test
  @Order(17)
  void searchingMatchesAnExactAddressAndAPartialOne() {
    var exact = get("/api/v1/search?q=https%3A%2F%2Fexample.com%2Fapi-created");
    assertEquals(200, exact.status().intValue());
    assertTrue(parse(exact.body()).has(watchUuid), "an exact address matches");

    var partialOff = get("/api/v1/search?q=api-created");
    assertFalse(parse(partialOff.body()).has(watchUuid), "a fragment does not, by default");

    var partialOn = get("/api/v1/search?q=api-created&partial=1");
    assertTrue(parse(partialOn.body()).has(watchUuid), "a fragment does when asked");
  }

  @Test
  @Order(18)
  void theSystemInformationCounts() {
    var response = get("/api/v1/systeminfo");
    assertEquals(200, response.status().intValue());
    JsonNode body = parse(response.body());
    assertTrue(body.get("watch_count").asInt() >= 1);
    assertTrue(body.has("queue_size"));
    assertTrue(body.has("overdue_watches"));
    assertTrue(body.has("uptime"));
    assertFalse(body.get("version").asText().isEmpty());
  }

  @Test
  @Order(19)
  void aTagCanBeCreatedReadAndDeleted() {
    var created = send("POST", "/api/v1/tag", "{\"title\":\"api tag\"}");
    assertEquals(201, created.status().intValue(), created.body());
    tagUuid = parse(created.body()).get("uuid").asText();

    var read = get("/api/v1/tag/" + tagUuid);
    assertEquals(200, read.status().intValue(), read.body());
    assertEquals("api tag", parse(read.body()).get("title").asText());
    // A tag is the same record as a watch underneath, and the fields only a check writes are
    // dropped rather than reported as zero.
    assertFalse(parse(read.body()).has("last_checked"));

    var listed = get("/api/v1/tags");
    assertTrue(parse(listed.body()).has(tagUuid));

    var deleted = send("DELETE", "/api/v1/tag/" + tagUuid, null);
    assertEquals(204, deleted.status().intValue());
    assertEquals(404, get("/api/v1/tag/" + tagUuid).status().intValue());
  }

  @Test
  @Order(20)
  void aTagColourThatIsNotAColourIsRefused() {
    var response =
        send("POST", "/api/v1/tag", "{\"title\":\"bad colour\",\"tag_colour\":\"red;}\"}");
    assertEquals(400, response.status().intValue(), response.body());
    assertTrue(response.body().contains("must be a hex colour"));
  }

  @Test
  @Order(21)
  void notificationAddressesCanBeListedReplacedAndRemoved() {
    var replaced =
        send(
            "PUT",
            "/api/v1/notifications",
            "{\"notification_urls\":[\"mailto://someone@example.com\"]}");
    assertEquals(200, replaced.status().intValue(), replaced.body());

    var listed = get("/api/v1/notifications");
    assertEquals(200, listed.status().intValue());
    assertEquals(
        "mailto://someone@example.com",
        parse(listed.body()).get("notification_urls").get(0).asText());

    var removed =
        send(
            "DELETE",
            "/api/v1/notifications",
            "{\"notification_urls\":[\"mailto://someone@example.com\"]}");
    assertEquals(204, removed.status().intValue());
    assertEquals(0, parse(get("/api/v1/notifications").body()).get("notification_urls").size());
  }

  @Test
  @Order(22)
  void anAddressNothingCanDeliverToIsRefused() {
    var response =
        send(
            "PUT",
            "/api/v1/notifications",
            "{\"notification_urls\":[\"nosuchscheme://wherever\"]}");
    assertEquals(400, response.status().intValue(), response.body());
    assertTrue(response.body().contains("not a valid AppRise URL"));
  }

  @Test
  @Order(23)
  void aListOfAddressesCanBeImported() {
    String body = "https://example.com/imported-one\nhttps://example.com/imported-two\n";
    var response =
        httpClient
            .POST("/api/v1/import")
            .addHeader("Host", "localhost")
            .addHeader("x-api-key", key())
            .withRequestBody(
                akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8,
                body.getBytes(StandardCharsets.UTF_8))
            .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .invoke();
    assertEquals(200, response.status().intValue(), response.body());
    assertEquals(2, parse(response.body()).size());
  }

  @Test
  @Order(24)
  void animportParameterNobodyDeclaredIsRefused() {
    var response =
        httpClient
            .POST("/api/v1/import?not_a_field=1")
            .addHeader("Host", "localhost")
            .addHeader("x-api-key", key())
            .withRequestBody(
                akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8,
                "https://example.com/x".getBytes(StandardCharsets.UTF_8))
            .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .invoke();
    assertEquals(400, response.status().intValue(), response.body());
    assertTrue(response.body().contains("Unknown watch configuration parameter"));
  }

  @Test
  @Order(25)
  void aWatchCanBeDeleted() {
    var response = send("DELETE", "/api/v1/watch/" + watchUuid, null);
    assertEquals(204, response.status().intValue());
    assertEquals(404, get("/api/v1/watch/" + watchUuid).status().intValue());
  }

  @Test
  @Order(26)
  void deletingSomethingThatIsNotThereIsARefusalRatherThanASilentSuccess() {
    var response =
        send("DELETE", "/api/v1/watch/00000000-0000-4000-8000-000000000000", null);
    assertEquals(400, response.status().intValue());
  }
}
