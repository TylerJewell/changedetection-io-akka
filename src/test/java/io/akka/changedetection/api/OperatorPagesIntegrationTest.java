package io.akka.changedetection.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The pages an operator uses that are not the watch list: settings, notifications, importing,
 * archives, the feeds and the queue.
 *
 * <p>Every one is served from the original's own template, so a page that renders is a page
 * whose template found everything it asked the server for -- which is the half of "the
 * interface is reused" that a screenshot cannot show.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorPagesIntegrationTest extends TestKitSupport {

  private static final Pattern RSS_TOKEN = Pattern.compile("rss[^\"]*token=([0-9a-f]{32})");

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
        .withRequestBody(
            akka.http.javadsl.model.ContentTypes.parse("application/x-www-form-urlencoded"),
            encoded.getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  private String rssToken() {
    Matcher found = RSS_TOKEN.matcher(get("/").body());
    return found.find() ? found.group(1) : "";
  }

  @Test
  @Order(1)
  void theSettingsPageIsServed() {
    var response = get("/settings");
    assertEquals(200, response.status().intValue(), response.body());
    assertTrue(response.body().contains("id=\"api-key\""), "the key it generated is shown");
    assertTrue(response.body().contains("time_between_check"), "and the shared interval");
  }

  @Test
  @Order(2)
  void theNotificationPageIsServedAndTheIndexSendsYouToIt() {
    var index = get("/settings/notifications/");
    assertEquals(302, index.status().intValue(), "one backend, so the index is a signpost");

    var apprise = get("/settings/notifications/apprise");
    assertEquals(200, apprise.status().intValue(), apprise.body());
    assertTrue(apprise.body().contains("notification_urls"));
  }

  @Test
  @Order(3)
  void aNotificationSettingSavedOnItsOwnPageSurvivesASettingsSave() {
    var saved =
        postForm(
            "/settings/notifications/apprise",
            "notification_urls=mailto%3A%2F%2Fkeeper%40example.com"
                + "&notification_title=Kept&notification_body=Kept+body"
                + "&notification_format=text&base_url=&save_button=Save");
    assertEquals(302, saved.status().intValue(), saved.body());
    assertTrue(get("/settings/notifications/apprise").body().contains("keeper@example.com"));

    // The settings form still declares the notification fields, inherited from the shared
    // shape. Saving it must leave them alone rather than merging its empty defaults over them.
    var settings =
        postForm(
            "/settings",
            "requests-time_between_check-hours=3&requests-workers=1"
                + "&application-pager_size=50&save_button=Save");
    assertTrue(settings.status().intValue() < 400, settings.body());
    assertTrue(
        get("/settings/notifications/apprise").body().contains("keeper@example.com"),
        "the other page's setting is still there");
  }

  @Test
  @Order(4)
  void theApiKeyCanBeRegenerated() {
    String before = keyOnPage();
    var response = get("/settings/reset-api-key");
    assertEquals(302, response.status().intValue());
    String after = keyOnPage();
    assertNotNull(after);
    assertFalse(after.isEmpty());
    assertFalse(after.equals(before), "a new key, not the same one");
  }

  private String keyOnPage() {
    Matcher found = Pattern.compile("id=\"api-key\">([^<]*)<").matcher(get("/settings").body());
    return found.find() ? found.group(1).strip() : "";
  }

  @Test
  @Order(5)
  void theNotificationLogIsServed() {
    var response = get("/settings/notification-logs");
    assertEquals(200, response.status().intValue(), response.body());
    assertTrue(
        response.body().contains("Notification logs are empty"),
        "an empty log says so rather than showing nothing");
  }

  @Test
  @Order(6)
  void schedulingAndMutingCanBeSuspendedForEverything() {
    assertEquals(302, get("/settings/toggle-all-paused").status().intValue());
    assertEquals(302, get("/settings/toggle-all-muted").status().intValue());
    // And back, so the rest of this run is not affected by them.
    assertEquals(302, get("/settings/toggle-all-paused").status().intValue());
    assertEquals(302, get("/settings/toggle-all-muted").status().intValue());
  }

  @Test
  @Order(7)
  void theImportPageIsServedAndTakesAList() {
    assertEquals(200, get("/imports/import").status().intValue());

    var response =
        postForm(
            "/imports/import",
            "urls=https%3A%2F%2Fexample.com%2Fimport-a%0Ahttps%3A%2F%2Fexample.com%2Fimport-b"
                + "&processor=text_json_diff");
    assertEquals(302, response.status().intValue(), "everything read, so back to the list");
  }

  @Test
  @Order(8)
  void anImportLineThatIsNotAnAddressComesBackForAnotherLook() {
    var response =
        postForm(
            "/imports/import",
            "urls=https%3A%2F%2Fexample.com%2Fimport-c%0Anot+an+address"
                + "&processor=text_json_diff");
    assertEquals(200, response.status().intValue(), "something remained, so the page again");
    // A line is split at its first space, and everything after it is read as the tags to put
    // the watch in. So the part offered back is the first word, not the whole line -- which is
    // what the original leaves behind for the same input.
    assertTrue(
        response.body().contains("rows=\"25\">not</textarea>"),
        "the first word is offered back: " + response.body());
  }

  @Test
  @Order(9)
  void anArchiveCanBeTakenListedAndDownloaded() {
    assertEquals(200, get("/backups/").status().intValue());

    var requested = get("/backups/request-backup");
    assertEquals(302, requested.status().intValue());

    String listing = get("/backups/").body();
    Matcher found =
        Pattern.compile("(changedetection-backup-\\d+\\.zip)").matcher(listing);
    assertTrue(found.find(), "the archive is listed: " + listing);

    var downloaded =
        httpClient
            .GET("/backups/download/" + found.group(1))
            .addHeader("Host", "localhost")
            .parseResponseBody(bytes -> bytes)
            .invoke();
    assertEquals(200, downloaded.status().intValue());
    byte[] archive = downloaded.body();
    assertTrue(archive.length > 0);
    assertEquals('P', (char) archive[0], "a zip, by its own first two bytes");
    assertEquals('K', (char) archive[1]);
  }

  @Test
  @Order(10)
  void anArchiveNameThatIsNotOneIsRefused() {
    var response = get("/backups/download/..%2F..%2Fsecret.txt");
    assertTrue(
        response.status().intValue() == 400 || response.status().intValue() == 404,
        "refused rather than served: " + response.status());
  }

  @Test
  @Order(11)
  void theRestorePageIsServed() {
    var response = get("/backups/restore");
    assertEquals(200, response.status().intValue(), response.body());
    assertTrue(response.body().contains("zip_file"));
  }

  @Test
  @Order(12)
  void archivesCanBeRemoved() {
    assertEquals(302, postForm("/backups/remove-backups", "").status().intValue());
    assertFalse(
        get("/backups/").body().contains("changedetection-backup-"),
        "and the list is empty afterwards");
  }

  @Test
  @Order(13)
  void theFeedIsRefusedWithoutTheTokenAndServedWithIt() {
    var refused = get("/rss");
    assertEquals(403, refused.status().intValue());
    assertTrue(refused.body().contains("Access denied, bad token"));

    String token = rssToken();
    assertFalse(token.isEmpty(), "the list page carries a link to the feed with its token");
    var served = get("/rss?token=" + token);
    assertEquals(200, served.status().intValue(), served.body());
    assertTrue(served.body().startsWith("<?xml"), "a feed document");
    assertTrue(served.body().contains("<title>changedetection.io</title>"));
  }

  @Test
  @Order(14)
  void aFeedForAWatchWithoutEnoughHistorySaysSo() {
    String token = rssToken();
    var response = get("/rss/watch/00000000-0000-4000-8000-000000000000?token=" + token);
    assertEquals(404, response.status().intValue());
    assertTrue(response.body().contains("not found"));
  }

  @Test
  @Order(15)
  void aFeedForATagThatDoesNotExistSaysSo() {
    String token = rssToken();
    var response = get("/rss/tag/00000000-0000-4000-8000-000000000000?token=" + token);
    assertEquals(404, response.status().intValue());
    assertTrue(response.body().contains("Tag with UUID"));
  }

  @Test
  @Order(16)
  void theQueuePagesAreServed() {
    assertEquals(200, get("/queue").status().intValue());
    var asData = get("/queue.json");
    assertEquals(200, asData.status().intValue());
    assertTrue(asData.body().contains("queue"), asData.body());
  }

  @Test
  @Order(17)
  void theThreeRoutesAMonitorAsksAreServed() {
    assertEquals(200, get("/worker-health").status().intValue());
    assertEquals(200, get("/queue-status").status().intValue());
    assertEquals(200, get("/gc-cleanup").status().intValue());
  }

  @Test
  @Order(18)
  void theAddWatchPageIsServed() {
    var response = get("/add-watch-ui/");
    assertEquals(200, response.status().intValue(), response.body());
  }

  @Test
  @Order(19)
  void aLiveLookAtAnAddressNobodyMayFetchIsRefusedBeforeAnyBrowserIsOpened() {
    var response = get("/add-watch-ui/snapshot?url=http%3A%2F%2F127.0.0.1%2Fadmin");
    assertEquals(400, response.status().intValue(), response.body());
  }

  @Test
  @Order(20)
  void theTagPagesAreServed() {
    assertEquals(200, get("/tags/list").status().intValue());
    var added = postForm("/tags/add", "name=from+the+tags+page");
    assertEquals(302, added.status().intValue());
    assertTrue(get("/tags/list").body().contains("from the tags page"));
  }
}
