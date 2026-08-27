package io.akka.changedetection.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Puts the benchmark's surface requests to this service and writes down what came back.
 *
 * <p>This is half of a comparison, not a test of its own: the same requests go to the
 * original running as a server, and {@code bench/compare_surface.py} puts the two answers
 * side by side. What is recorded is the status, the kind of document, and a shape rather
 * than the bytes -- the two systems' pages carry different identifiers and moments, so
 * comparing them byte for byte would report a difference on every line.
 *
 * <p>It runs as a test because the runtime this service needs is started by the test kit,
 * and the shared one is not running on this machine.
 */
class SurfaceAnswersIntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Path BENCH =
      Path.of("..", "changedetection-io-port", "bench").toAbsolutePath().normalize();

  /** The landmarks a page may draw, named the way the source-side runner names them. */
  private static final String[][] LANDMARKS = {
    {"the shell", "<title>Change Detection"},
    {"the menu", "id=\"pure-menu-horizontal"},
    {"the watch table", "class=\"watch-table"},
    {"the add form", "id=\"new-watch-form\""},
    {"the settings form", "time_between_check"},
    {"the notification fields", "notification_urls"},
    {"the api key", "id=\"api-key\""},
    {"the import form", "name=\"urls\""},
    {"the archive list", "changedetection-backup-"},
    {"the restore form", "name=\"zip_file\""},
    {"the tag list", "tags"},
    {"the sign-in form", "name=\"password\""},
    {"the feed link", "rss"},
  };

  private record Answer(int status, String contentType, String body) {}

  private Answer send(String method, String path, String json, boolean withKey) {
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
    var prepared = withBody.addHeader("Host", "localhost");
    if (withKey) {
      prepared = prepared.addHeader("x-api-key", apiKey());
    }
    var response =
        prepared.parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8)).invoke();
    String contentType =
        response.httpResponse().entity().getContentType() == null
            ? ""
            : response.httpResponse().entity().getContentType().toString();
    return new Answer(response.status().intValue(), contentType, response.body());
  }

  private String apiKey;

  private String apiKey() {
    if (apiKey == null) {
      apiKey = readFromSettings("id=\"api-key\">([^<]*)<");
    }
    return apiKey;
  }

  private String feedToken;

  private String feedToken() {
    if (feedToken == null) {
      feedToken = readFromSettings("rss[^\"]*token=([0-9a-f]{32})");
    }
    return feedToken;
  }

  private String readFromSettings(String pattern) {
    String body = send("GET", pattern.startsWith("rss") ? "/" : "/settings", null, false).body();
    Matcher found = Pattern.compile(pattern).matcher(body);
    return found.find() ? found.group(1).strip() : "";
  }

  private static ObjectNode shape(String body, String contentType) {
    ObjectNode out = MAPPER.createObjectNode();
    String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
    if (type.contains("json")) {
      try {
        JsonNode parsed = MAPPER.readTree(body);
        if (parsed.isObject()) {
          List<String> names = new ArrayList<>();
          parsed.fieldNames().forEachRemaining(names::add);
          java.util.Collections.sort(names);
          // An answer keyed by identifier is compared by the shape of a row rather than by
          // the keys: the two systems made their own identifiers and always differ on them,
          // which would report a difference about nothing on every list.
          boolean keyedByIdentifier =
              !names.isEmpty() && names.stream().allMatch(n -> n.matches("[0-9a-f-]{36}"));
          if (keyedByIdentifier) {
            out.put("kind", "object keyed by identifier");
            out.put("rows", names.size());
            ArrayNode rowNames = out.putArray("row_names");
            JsonNode first = parsed.get(names.get(0));
            if (first != null && first.isObject()) {
              List<String> inner = new ArrayList<>();
              first.fieldNames().forEachRemaining(inner::add);
              java.util.Collections.sort(inner);
              inner.forEach(rowNames::add);
            }
          } else {
            out.put("kind", "object");
            ArrayNode listed = out.putArray("names");
            names.stream().limit(40).forEach(listed::add);
          }
        } else if (parsed.isArray()) {
          out.put("kind", "list");
          out.put("length", parsed.size());
        } else {
          out.put("kind", parsed.isTextual() ? "str" : parsed.getNodeType().name().toLowerCase());
          out.put("value", parsed.asText());
        }
      } catch (Exception e) {
        out.put("kind", "unreadable json");
      }
      return out;
    }
    if (type.contains("xml")) {
      out.put("kind", "feed");
      int items = 0;
      int at = 0;
      while ((at = body.indexOf("<item>", at)) >= 0) {
        items++;
        at += 6;
      }
      out.put("items", items);
      return out;
    }
    if (type.contains("yaml")) {
      out.put("kind", "description");
      out.put("names_the_routes", body.contains("/api/v1/watch"));
      return out;
    }
    if (type.contains("html")) {
      out.put("kind", "page");
      ArrayNode found = out.putArray("landmarks");
      for (String[] landmark : LANDMARKS) {
        if (body.toLowerCase(java.util.Locale.ROOT)
            .contains(landmark[1].toLowerCase(java.util.Locale.ROOT))) {
          found.add(landmark[0]);
        }
      }
      return out;
    }
    out.put("kind", "text");
    out.put("length", body == null ? 0 : body.length());
    return out;
  }

  private void waitUntilServing() throws Exception {
    for (int attempt = 0; attempt < 60; attempt++) {
      if (send("GET", "/", null, false).status() == 200) {
        return;
      }
      Thread.sleep(250);
    }
    throw new IllegalStateException("the service never answered its own watch list");
  }

  @Test
  void theSurfaceIsAnsweredAndWrittenDown() throws Exception {
    // The routes are registered a moment after the runtime reports itself started, and this
    // class is often the only one in a run, so nothing else has warmed them. Waiting for the
    // list to answer is the difference between recording the service's answers and recording
    // a router that has not finished reading its own table.
    waitUntilServing();

    Path requestsFile = BENCH.resolve("surface.json");
    assertTrue(Files.isRegularFile(requestsFile), "no " + requestsFile);
    JsonNode requests = MAPPER.readTree(Files.readString(requestsFile));

    // One watch and one tag, so that a list is not empty on either side. The original's own
    // first run seeds a watch of its own, which is why the comparison is of shape not of
    // contents: what is in the list differs, that there is a list does not.
    send("POST", "/api/v1/watch", "{\"url\":\"https://example.com/surface\"}", true);
    send("POST", "/api/v1/tag", "{\"title\":\"surface\"}", true);

    ObjectNode answers = MAPPER.createObjectNode();
    for (JsonNode request : requests) {
      String path = request.get("path").asText().replace("RSS_TOKEN", feedToken());
      boolean withKey =
          request.path("key").asBoolean(true) && path.startsWith("/api/");
      String json =
          request.has("json") ? MAPPER.writeValueAsString(request.get("json")) : null;
      Answer answer = send(request.get("method").asText(), path, json, withKey);

      ObjectNode recorded = answers.putObject(request.get("name").asText());
      recorded.put("status", answer.status());
      recorded.set("shape", shape(answer.body(), answer.contentType()));
    }

    Files.writeString(
        BENCH.resolve("port-surface-answers.json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(answers),
        StandardCharsets.UTF_8);

    Answer list = send("GET", "/", null, false);
    assertTrue(
        list.status() == 200,
        "the watch list answered " + list.status() + " content-type " + list.contentType()
            + " body " + list.body().substring(0, Math.min(400, list.body().length())));
    assertFalse(answers.isEmpty(), "nothing was recorded");
    assertTrue(answers.size() == requests.size(), "every request was put");
  }
}
