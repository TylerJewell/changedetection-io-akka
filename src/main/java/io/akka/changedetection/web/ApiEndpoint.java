package io.akka.changedetection.web;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.SettingsEntity;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.diff.DiffRenderer;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.model.WatchDefaults;
import io.akka.changedetection.notification.ServiceTweaks;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The programmatic interface: everything a caller can do without the pages.
 *
 * <p>It is a separate surface from the interface rather than the same one in a different coat.
 * The interface authenticates with a session cookie and answers with markup; this authenticates
 * with a key in a header and answers with data, and a caller of one never sees the other's
 * failure modes. The one thing they share is the store underneath.
 *
 * <p>Which fields a caller may send is taken from {@link ApiSpec} rather than restated here, so
 * a field added to the published description is accepted without a second edit.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ApiEndpoint extends AbstractHttpEndpoint {

  /** Above this many addresses an import answers before it has finished doing the work. */
  private static final int IMPORT_BACKGROUND_THRESHOLD = 20;

  /** Above this many watches a bulk recheck answers before it has finished queueing. */
  private static final int RECHECK_BACKGROUND_THRESHOLD = 20;

  private final ComponentClient componentClient;

  public ApiEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  // ---------------------------------------------------------------- one watch

  @Get("/api/v1/watch/{uuid}")
  public HttpResponse getWatch(String uuid) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return message(StatusCodes.NOT_FOUND, "No watch exists with the UUID of " + uuid);
    }

    String recheck = query("recheck");
    if (!recheck.isEmpty()) {
      operations.queueCheck(uuid);
      return Requests.json(StatusCodes.OK, "OK");
    }
    String paused = query("paused");
    if ("paused".equals(paused) || "unpaused".equals(paused)) {
      operations.update(uuid, Map.of("paused", "paused".equals(paused)));
      return Requests.json(StatusCodes.OK, "OK");
    }
    String muted = query("muted");
    if ("muted".equals(muted) || "unmuted".equals(muted)) {
      operations.update(uuid, Map.of("notification_muted", "muted".equals(muted)));
      return Requests.json(StatusCodes.OK, "OK");
    }

    Watch watch = state.asWatch();
    Map<String, Object> body = new LinkedHashMap<>(watch.asMap());
    body.put("history_n", watch.historyCount());
    body.put("last_changed", watch.lastChanged());
    body.put("viewed", watch.viewed());
    body.put("link", watch.link(text -> text));

    // A tag that overrides its watches decides the price settings for all of them, so the
    // answer says which of the two the caller is looking at rather than leaving them to guess.
    Map<String, Object> restock = restockConfig(store, uuid);
    body.put("processor_config_restock_diff", restock.get("config"));
    body.put("processor_config_restock_diff_source", restock.get("source"));
    return Requests.json(stripInternal(body));
  }

  @Delete("/api/v1/watch/{uuid}")
  public HttpResponse deleteWatch(String uuid) {
    Operations operations = new Operations(componentClient);
    HttpResponse refusal = requireKey(operations.store());
    if (refusal != null) {
      return refusal;
    }
    if (!operations.store().watch(uuid).exists()) {
      return message(StatusCodes.BAD_REQUEST, "No watch exists with the UUID of " + uuid);
    }
    operations.delete(uuid);
    return HttpResponse.create().withStatus(StatusCodes.NO_CONTENT);
  }

  @Put("/api/v1/watch/{uuid}")
  public HttpResponse updateWatch(String uuid, HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return message(StatusCodes.NOT_FOUND, "No watch exists with the UUID of " + uuid);
    }
    Map<String, Object> json = stripInternal(Requests.json(body));

    String proxyError = checkProxy(store, json.get("proxy"));
    if (proxyError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, proxyError);
    }
    String intervalError = checkIntervalGiven(json);
    if (intervalError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, intervalError);
    }
    String notificationError = checkNotificationUrls(json.get("notification_urls"));
    if (notificationError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, notificationError);
    }
    if (json.containsKey("url")) {
      String urlError = checkUrl(json.get("url"), store);
      if (urlError != null) {
        return Requests.json(StatusCodes.BAD_REQUEST, urlError);
      }
      json.put("url", String.valueOf(json.get("url")).strip());
    }

    Map<String, Object> processorConfig = extractProcessorConfig(json);
    for (String field : ignoredWatchFields()) {
      json.remove(field);
    }
    Set<String> valid = new LinkedHashSet<>(ApiSpec.properties("WatchBase").keySet());
    valid.add("last_viewed");
    List<String> unknown = new ArrayList<>(new TreeSet<>(unknownOf(json.keySet(), valid)));
    if (!unknown.isEmpty()) {
      return Requests.json(
          StatusCodes.BAD_REQUEST, "Unknown field(s): " + String.join(", ", unknown));
    }

    operations.update(uuid, json);
    saveProcessorConfig(store, uuid, processorConfig);
    return Requests.json(StatusCodes.OK, "OK");
  }

  // ---------------------------------------------------------- the watch list

  @Get("/api/v1/watch")
  public HttpResponse listWatches() {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    String tagLimit = query("tag").toLowerCase(Locale.ROOT);
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Watch> all = store.allWatches();
    for (Map.Entry<String, Watch> entry : all.entrySet()) {
      Map<String, Map<String, Object>> tags = store.tagsForWatch(entry.getValue());
      if (!tagLimit.isEmpty()
          && tags.values().stream()
              .noneMatch(
                  tag ->
                      String.valueOf(tag.getOrDefault("title", ""))
                          .toLowerCase(Locale.ROOT)
                          .equals(tagLimit))) {
        continue;
      }
      out.put(entry.getKey(), summaryOf(entry.getValue(), new ArrayList<>(tags.keySet())));
    }

    if (!query("recheck_all").isEmpty()) {
      List<String> uuids = new ArrayList<>(all.keySet());
      if (uuids.size() < RECHECK_BACKGROUND_THRESHOLD) {
        List<String> queued = new ArrayList<>();
        for (String uuid : uuids) {
          if (!Site.queued().contains(uuid)) {
            operations.queueCheck(uuid);
            queued.add(uuid);
          }
        }
        int skipped = uuids.size() - queued.size();
        String status =
            skipped > 0
                ? "OK, queued " + queued.size() + " watches for rechecking (" + skipped
                    + " already queued or running)"
                : "OK, queued " + queued.size() + " watches for rechecking";
        return Requests.json(StatusCodes.OK, Map.of("status", status));
      }
      Site.queueAll(uuids);
      return Requests.json(
          StatusCodes.ACCEPTED,
          Map.of("status", "OK, queueing " + uuids.size() + " watches in background"));
    }
    return Requests.json(out);
  }

  @Post("/api/v1/watch")
  public HttpResponse createWatch(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> json = stripInternal(Requests.json(body));
    Object rawUrl = json.get("url");
    String url = rawUrl == null ? "" : String.valueOf(rawUrl).strip();
    if (!UrlSafety.isSafeValidUrl(url, allowFileUri(store))) {
      return Requests.json(StatusCodes.BAD_REQUEST, "Invalid or unsupported URL");
    }
    String proxyError = checkProxy(store, json.get("proxy"));
    if (proxyError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, proxyError);
    }
    String intervalError = checkIntervalGiven(json);
    if (intervalError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, intervalError);
    }
    String notificationError = checkNotificationUrls(json.get("notification_urls"));
    if (notificationError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, notificationError);
    }

    Map<String, Object> extras = new LinkedHashMap<>(json);
    Map<String, Object> processorConfig = extractProcessorConfig(extras);
    // The interface renamed this field and the published interface did not follow, so the
    // singular spelling is still what a caller sends.
    String tags = extras.containsKey("tag") ? String.valueOf(extras.remove("tag")) : null;
    extras.remove("url");

    String watchLimit = System.getenv("PAGE_WATCH_LIMIT");
    if (watchLimit != null && !watchLimit.isBlank()) {
      try {
        int limit = Integer.parseInt(watchLimit.strip());
        int current = store.watchUuids().size();
        if (current >= limit) {
          return Requests.json(
              StatusCodes.TOO_MANY_REQUESTS,
              "Watch limit reached (" + current + "/" + limit + " watches). Cannot add more"
                  + " watches.");
        }
      } catch (NumberFormatException e) {
        // An unreadable limit is no limit, which is what the original does with it too.
      }
    }

    String created = operations.addWatch(url, tags, extras);
    if (created == null) {
      return Requests.json(StatusCodes.BAD_REQUEST, "Invalid or unsupported URL");
    }
    saveProcessorConfig(store, created, processorConfig);
    return Requests.json(StatusCodes.CREATED, Map.of("uuid", created));
  }

  // ------------------------------------------------------------------ history

  @Get("/api/v1/watch/{uuid}/history")
  public HttpResponse watchHistory(String uuid) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return message(StatusCodes.NOT_FOUND, "No watch exists with the UUID of " + uuid);
    }
    Map<String, Object> history = new LinkedHashMap<>();
    for (Long timestamp : state.history()) {
      history.put(String.valueOf(timestamp), String.valueOf(timestamp) + ".txt");
    }
    return Requests.json(history);
  }

  @Get("/api/v1/watch/{uuid}/history/{timestamp}")
  public HttpResponse watchSnapshot(String uuid, String timestamp) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return message(StatusCodes.NOT_FOUND, "No watch exists with the UUID of " + uuid);
    }
    List<Long> history = state.history();
    if (history.isEmpty()) {
      return message(
          StatusCodes.NOT_FOUND, "Watch found but no history exists for the UUID " + uuid);
    }
    String wanted = timestamp;
    if ("latest".equals(wanted)) {
      wanted = String.valueOf(history.get(history.size() - 1));
    }
    Long key = asTimestamp(wanted);
    if (key == null || !history.contains(key)) {
      return message(
          StatusCodes.NOT_FOUND, "No history snapshot found for timestamp '" + timestamp + "'");
    }

    if (!query("html").isEmpty()) {
      String content = store.sideStore(uuid, "html-" + key);
      if (content == null || content.isEmpty()) {
        return Requests.text(StatusCodes.NOT_FOUND, "No content found");
      }
      // The bytes are markup, and are still served as text: this is a programmatic answer, and
      // labelling it as markup is what lets a script planted in a watched page run in this
      // service's own origin when somebody opens the address in a browser.
      String safe = wanted.replaceAll("[^0-9A-Za-z_-]", "");
      if (safe.length() > 32) {
        safe = safe.substring(0, 32);
      }
      if (safe.isEmpty()) {
        safe = "snapshot";
      }
      return HttpResponse.create()
          .withStatus(StatusCodes.OK)
          .addHeader(RawHeader.create("X-Content-Type-Options", "nosniff"))
          .addHeader(
              RawHeader.create("Content-Disposition", "attachment; filename=\"snapshot-" + safe
                  + "\""))
          .withEntity(ContentTypes.TEXT_PLAIN_UTF8, content.getBytes(StandardCharsets.UTF_8));
    }
    String content = store.snapshot(uuid, key);
    return Requests.text(content == null ? "" : content);
  }

  @Get("/api/v1/watch/{uuid}/history/{from}/diff/{to}")
  public HttpResponse historyDiff(String uuid, String from, String to) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return message(StatusCodes.NOT_FOUND, "No watch exists with the UUID of " + uuid);
    }
    List<Long> history = state.history();
    if (history.isEmpty()) {
      return message(
          StatusCodes.NOT_FOUND, "Watch found but no history exists for the UUID " + uuid);
    }
    String wantedTo = "latest".equals(to) ? String.valueOf(history.get(history.size() - 1)) : to;
    String wantedFrom = from;
    if ("previous".equals(from)) {
      if (history.size() < 2) {
        return message(
            StatusCodes.NOT_FOUND,
            "Not enough history entries. Need at least 2 snapshots for 'previous'");
      }
      wantedFrom = String.valueOf(history.get(history.size() - 2));
    }
    Long fromKey = asTimestamp(wantedFrom);
    Long toKey = asTimestamp(wantedTo);
    if (fromKey == null || !history.contains(fromKey)) {
      return message(
          StatusCodes.NOT_FOUND, "From timestamp " + wantedFrom + " not found in watch history");
    }
    if (toKey == null || !history.contains(toKey)) {
      return message(
          StatusCodes.NOT_FOUND, "To timestamp " + wantedTo + " not found in watch history");
    }

    String format = query("format").isEmpty() ? "text" : query("format").toLowerCase(Locale.ROOT);
    if (!ServiceTweaks.formats().contains(format)) {
      return message(
          StatusCodes.BAD_REQUEST,
          "Invalid format. Must be one of: " + String.join(", ", ServiceTweaks.formats()));
    }
    boolean noMarkup = Fields.truthy(query("no_markup"));
    boolean wordDiff = Fields.truthy(query("word_diff"));
    if ("diffWords".equals(query("type"))) {
      wordDiff = true;
    }

    DiffRenderer.Options options = new DiffRenderer.Options();
    options.includeEqual = !Fields.truthy(query("changesOnly"));
    options.ignoreJunk = Fields.truthy(query("ignoreWhitespace"));
    options.includeRemoved = booleanQuery("removed", true);
    options.includeAdded = booleanQuery("added", true);
    options.includeReplaced = booleanQuery("replaced", true);
    options.wordDiff = wordDiff;

    String previous = store.snapshot(uuid, fromKey);
    String newest = store.snapshot(uuid, toKey);
    String content =
        DiffRenderer.render(previous == null ? "" : previous, newest == null ? "" : newest,
            options);

    if (noMarkup) {
      return Requests.text(content);
    }
    if ("htmlcolor".equals(format)) {
      return Requests.bytes(
          StatusCodes.OK,
          ContentTypes.TEXT_HTML_UTF8,
          DiffEndpoint.applyColour(content).getBytes(StandardCharsets.UTF_8));
    }
    ServiceTweaks.Result tweaked = ServiceTweaks.apply("", content, "", format);
    String body = tweaked.body();
    if (format.contains("html")) {
      body = body.replaceAll("\r?\n", "<br>\r\n");
      return Requests.bytes(
          StatusCodes.OK, ContentTypes.TEXT_HTML_UTF8, body.getBytes(StandardCharsets.UTF_8));
    }
    return Requests.text(body);
  }

  @Get("/api/v1/watch/{uuid}/favicon")
  public HttpResponse watchFavicon(String uuid) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    if (!store.watch(uuid).exists()) {
      return message(StatusCodes.NOT_FOUND, "No watch exists with the UUID of " + uuid);
    }
    String name = store.sideStore(uuid, "favicon-name");
    String stored = store.sideStore(uuid, "favicon");
    if (name == null || name.isEmpty() || stored == null || stored.isEmpty()) {
      return message(StatusCodes.NOT_FOUND, "No Favicon available for " + uuid);
    }
    akka.http.javadsl.model.ContentType type = Requests.typeFor(name);
    // A stored page served as a picture would show as a broken image on every row; anything
    // that reads as text is refused rather than sent.
    if (type.toString().startsWith("text/")) {
      return message(StatusCodes.NOT_FOUND, "No Favicon available for " + uuid);
    }
    byte[] bytes;
    try {
      bytes = java.util.Base64.getDecoder().decode(stored);
    } catch (IllegalArgumentException e) {
      bytes = stored.getBytes(StandardCharsets.UTF_8);
    }
    return Requests.bytes(StatusCodes.OK, type, bytes)
        .addHeader(RawHeader.create("Cache-Control", "max-age=300, must-revalidate"));
  }

  // --------------------------------------------------------------------- tags

  @Get("/api/v1/tags")
  public HttpResponse listTags() {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : store.tags().entrySet()) {
      Map<String, Object> tag = entry.getValue();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date_created", tag.getOrDefault("date_created", 0));
      row.put("notification_muted", tag.getOrDefault("notification_muted", false));
      row.put("title", tag.getOrDefault("title", ""));
      row.put("uuid", tag.get("uuid"));
      out.put(entry.getKey(), row);
    }
    return Requests.json(out);
  }

  @Get("/api/v1/tag/{uuid}")
  public HttpResponse getTag(String uuid) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> tag = store.tags().get(uuid);
    if (tag == null) {
      return message(StatusCodes.NOT_FOUND, "No tag exists with the UUID of " + uuid);
    }

    if (!query("recheck").isEmpty()) {
      List<Map.Entry<String, Watch>> ordered = new ArrayList<>(store.allWatches().entrySet());
      ordered.sort(
          java.util.Comparator.comparingLong(
              entry -> ApiSupport.longOf(entry.getValue().asMap().get("last_checked"), 0L)));
      List<String> queued = new ArrayList<>();
      for (Map.Entry<String, Watch> entry : ordered) {
        Map<String, Object> fields = entry.getValue().asMap();
        Object tags = fields.get("tags");
        boolean carries = tags instanceof List<?> list && list.contains(uuid);
        if (!Fields.truthy(fields.get("paused")) && carries) {
          queued.add(entry.getKey());
        }
      }
      if (queued.size() < RECHECK_BACKGROUND_THRESHOLD) {
        queued.forEach(operations::queueCheck);
        return Requests.json(
            StatusCodes.OK,
            Map.of("status", "OK, queued " + queued.size() + " watches for rechecking"));
      }
      Site.queueAll(queued);
      return Requests.json(
          StatusCodes.ACCEPTED,
          Map.of("status", "OK, queueing " + queued.size() + " watches in background"));
    }

    String muted = query("muted");
    if ("muted".equals(muted) || "unmuted".equals(muted)) {
      componentClient
          .forKeyValueEntity(SettingsEntity.ID)
          .method(SettingsEntity::updateTag)
          .invoke(
              new SettingsEntity.UpdateTag(
                  uuid, Map.of("notification_muted", "muted".equals(muted))));
      return Requests.json(StatusCodes.OK, "OK");
    }

    Map<String, Object> clean = new LinkedHashMap<>(tag);
    WATCH_ONLY_FIELDS.forEach(clean::remove);
    return Requests.json(stripInternal(clean));
  }

  @Delete("/api/v1/tag/{uuid}")
  public HttpResponse deleteTag(String uuid) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    if (store.tags().get(uuid) == null) {
      return message(StatusCodes.BAD_REQUEST, "No tag exists with the UUID of " + uuid);
    }
    ApiSupport.removeTag(componentClient, new Operations(componentClient), uuid);
    return HttpResponse.create().withStatus(StatusCodes.NO_CONTENT);
  }

  @Put("/api/v1/tag/{uuid}")
  public HttpResponse updateTag(String uuid, HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    if (store.tags().get(uuid) == null) {
      return message(StatusCodes.NOT_FOUND, "No tag exists with the UUID of " + uuid);
    }
    Map<String, Object> json = stripInternal(Requests.json(body));
    String notificationError = checkNotificationUrls(json.get("notification_urls"));
    if (notificationError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, notificationError);
    }
    ApiSpec.readOnly("Tag").forEach(json::remove);
    List<String> unknown =
        new ArrayList<>(new TreeSet<>(unknownOf(json.keySet(), ApiSpec.properties("Tag").keySet())));
    if (!unknown.isEmpty()) {
      return Requests.json(
          StatusCodes.BAD_REQUEST, "Unknown field(s): " + String.join(", ", unknown));
    }
    String colourError = checkTagColour(json.get("tag_colour"));
    if (colourError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, colourError);
    }

    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateTag)
        .invoke(new SettingsEntity.UpdateTag(uuid, json));
    // A tag carries settings its watches inherit, so changing it has to make each of them
    // compare its next fetch afresh rather than against a checksum taken under the old rules.
    ApiSupport.clearChecksumsForTag(store, uuid);
    return Requests.json(StatusCodes.OK, "OK");
  }

  @Post("/api/v1/tag")
  public HttpResponse createTag(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> json = stripInternal(Requests.json(body));
    List<String> unknown =
        new ArrayList<>(new TreeSet<>(unknownOf(json.keySet(), ApiSpec.properties("Tag").keySet())));
    if (!unknown.isEmpty()) {
      return Requests.json(
          StatusCodes.BAD_REQUEST, "Unknown field(s): " + String.join(", ", unknown));
    }
    String colourError = checkTagColour(json.get("tag_colour"));
    if (colourError != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, colourError);
    }
    String title = json.containsKey("title") ? String.valueOf(json.get("title")).strip() : "";
    String created = operations.addTag(title);
    if (created == null) {
      return Requests.json(StatusCodes.BAD_REQUEST, "Invalid or unsupported tag");
    }
    Map<String, Object> extra = new LinkedHashMap<>(json);
    extra.remove("title");
    if (!extra.isEmpty()) {
      componentClient
          .forKeyValueEntity(SettingsEntity.ID)
          .method(SettingsEntity::updateTag)
          .invoke(new SettingsEntity.UpdateTag(created, extra));
    }
    return Requests.json(StatusCodes.CREATED, Map.of("uuid", created));
  }

  // ------------------------------------------------------- the rest of it

  @Get("/api/v1/systeminfo")
  public HttpResponse systemInfo() {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    long now = System.currentTimeMillis() / 1000L;
    long fallback = Watch.thresholdSeconds(defaultInterval(store));
    List<String> overdue = new ArrayList<>();
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      Watch watch = entry.getValue();
      long threshold = watch.thresholdSeconds();
      if (threshold == 0) {
        threshold = fallback;
      }
      long since = now - ApiSupport.longOf(watch.asMap().get("last_checked"), 0L);
      // Five minutes of slack, because a watch edited a moment ago is not yet late.
      if (since - (5 * 60) > threshold) {
        overdue.add(entry.getKey());
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("queue_size", Site.queueSize());
    body.put("overdue_watches", overdue);
    body.put(
        "uptime",
        Math.round(
                (System.currentTimeMillis() - Site.startedAt().toEpochMilli()) / 10.0)
            / 100.0);
    body.put("watch_count", store.watchUuids().size());
    body.put("version", Site.VERSION);
    return Requests.json(body);
  }

  @Get("/api/v1/search")
  public HttpResponse search() {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    String q = query("q").strip();
    if (q.isEmpty()) {
      return message(StatusCodes.BAD_REQUEST, "Search query 'q' parameter is required");
    }
    String tagLimit = query("tag").strip();
    boolean partial = Fields.truthy(query("partial"));
    String needle = q.toLowerCase(Locale.ROOT).strip();

    String tagUuid = null;
    if (!tagLimit.isEmpty()) {
      for (Map.Entry<String, Map<String, Object>> entry : store.tags().entrySet()) {
        if (String.valueOf(entry.getValue().getOrDefault("title", ""))
            .equalsIgnoreCase(tagLimit)) {
          tagUuid = entry.getKey();
          break;
        }
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      Watch watch = entry.getValue();
      Map<String, Object> fields = watch.asMap();
      if (!tagLimit.isEmpty()) {
        Object tags = fields.get("tags");
        if (tagUuid == null || !(tags instanceof List<?> list) || !list.contains(tagUuid)) {
          continue;
        }
      }
      if (!matches(fields, needle, partial)) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("last_changed", watch.lastChanged());
      row.put("last_checked", fields.get("last_checked"));
      row.put("last_error", fields.get("last_error"));
      row.put("title", fields.get("title"));
      row.put("url", fields.get("url"));
      row.put("viewed", watch.viewed());
      out.put(entry.getKey(), row);
    }
    return Requests.json(out);
  }

  @Get("/api/v1/notifications")
  public HttpResponse listNotifications() {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    return Requests.json(
        Map.of("notification_urls", notificationUrls(store)));
  }

  @Post("/api/v1/notifications")
  public HttpResponse addNotifications(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    List<String> requested = stringList(Requests.json(body).get("notification_urls"));
    String error = checkNotificationUrls(requested);
    if (error != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, error);
    }
    List<String> existing = new ArrayList<>(notificationUrls(store));
    List<String> added = new ArrayList<>();
    for (String url : requested) {
      String clean = url.strip();
      if (!clean.isEmpty() && !existing.contains(clean)) {
        existing.add(clean);
        added.add(clean);
      }
    }
    if (added.isEmpty()) {
      return Requests.json(StatusCodes.BAD_REQUEST, "No valid notification URLs were added");
    }
    saveNotificationUrls(existing);
    return Requests.json(StatusCodes.CREATED, Map.of("notification_urls", added));
  }

  @Put("/api/v1/notifications")
  public HttpResponse replaceNotifications(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    Object raw = Requests.json(body).get("notification_urls");
    if (raw != null && !(raw instanceof List<?>)) {
      return Requests.json(StatusCodes.BAD_REQUEST, "Invalid input format");
    }
    List<String> requested = stringList(raw);
    String error = checkNotificationUrls(requested);
    if (error != null) {
      return Requests.json(StatusCodes.BAD_REQUEST, error);
    }
    List<String> clean = new ArrayList<>();
    for (String url : requested) {
      clean.add(url.strip());
    }
    saveNotificationUrls(clean);
    return Requests.json(Map.of("notification_urls", clean));
  }

  @Delete("/api/v1/notifications")
  public HttpResponse deleteNotifications(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    Object raw = Requests.json(body).get("notification_urls");
    if (raw != null && !(raw instanceof List<?>)) {
      return message(StatusCodes.BAD_REQUEST, "Expected a list of notification URLs.");
    }
    List<String> existing = new ArrayList<>(notificationUrls(store));
    List<String> deleted = new ArrayList<>();
    for (String url : stringList(raw)) {
      String clean = url.strip();
      if (existing.remove(clean)) {
        deleted.add(clean);
      }
    }
    if (deleted.isEmpty()) {
      return message(StatusCodes.BAD_REQUEST, "No matching notification URLs found.");
    }
    saveNotificationUrls(existing);
    return HttpResponse.create().withStatus(StatusCodes.NO_CONTENT);
  }

  @Post("/api/v1/import")
  public HttpResponse importWatches(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    HttpResponse refusal = requireKey(store);
    if (refusal != null) {
      return refusal;
    }
    Set<String> special = Set.of("tag", "tag_uuids", "dedupe", "proxy");
    Map<String, Object> extras = new LinkedHashMap<>();

    String proxy = query("proxy");
    if (!proxy.isEmpty()) {
      String proxyError = checkProxy(store, proxy);
      if (proxyError != null) {
        return Requests.json(StatusCodes.BAD_REQUEST, proxyError);
      }
      extras.put("proxy", proxy);
    }
    boolean dedupe = query("dedupe").isEmpty() || Fields.truthy(query("dedupe"));
    String tags = query("tag").isEmpty() ? null : query("tag");
    List<String> tagUuids =
        query("tag_uuids").isEmpty()
            ? new ArrayList<>()
            : new ArrayList<>(Arrays.asList(query("tag_uuids").split(",")));

    Map<String, Object> schema = ApiSpec.properties("WatchBase");
    for (Map.Entry<String, List<String>> entry : Requests.query(requestContext()).entrySet()) {
      String name = entry.getKey();
      if (special.contains(name)) {
        continue;
      }
      if (!schema.containsKey(name)) {
        return Requests.json(
            StatusCodes.BAD_REQUEST, "Unknown watch configuration parameter: " + name);
      }
      try {
        extras.put(name, converted(entry.getValue().get(0), schema.get(name)));
      } catch (RuntimeException e) {
        return Requests.json(
            StatusCodes.BAD_REQUEST,
            "Invalid value for parameter '" + name + "': " + e.getMessage());
      }
    }

    List<String> lines =
        Arrays.asList(new String(body.getData().toArray(), StandardCharsets.UTF_8).split("\r?\n"));
    List<String> toImport = new ArrayList<>();
    Set<String> known = new LinkedHashSet<>();
    for (Watch watch : store.allWatches().values()) {
      known.add(String.valueOf(watch.asMap().getOrDefault("url", "")));
    }
    for (String raw : lines) {
      String url = raw.strip();
      if (url.isEmpty()) {
        continue;
      }
      if (!UrlSafety.isSafeValidUrl(url, allowFileUri(store))) {
        return Requests.json(
            StatusCodes.BAD_REQUEST, "Invalid or unsupported URL - " + url);
      }
      if (dedupe && known.contains(url)) {
        continue;
      }
      toImport.add(url);
    }

    if (toImport.size() < IMPORT_BACKGROUND_THRESHOLD) {
      List<String> added = new ArrayList<>();
      for (String url : toImport) {
        Map<String, Object> withTags = new LinkedHashMap<>(extras);
        if (!tagUuids.isEmpty()) {
          withTags.put("tags", tagUuids);
        }
        added.add(operations.addWatch(url, tags, withTags));
      }
      return Requests.json(added);
    }
    for (String url : toImport) {
      Map<String, Object> withTags = new LinkedHashMap<>(extras);
      if (!tagUuids.isEmpty()) {
        withTags.put("tags", tagUuids);
      }
      operations.addWatch(url, tags, withTags);
    }
    return Requests.json(
        StatusCodes.ACCEPTED,
        Map.of(
            "status", "Importing " + toImport.size() + " URLs in background",
            "count", toImport.size()));
  }

  /** The published description of everything above, which needs no key to read. */
  @Get("/api/v1/full-spec")
  public HttpResponse fullSpec() {
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(
            akka.http.javadsl.model.ContentTypes.parse("application/yaml"),
            ApiSpec.specText().getBytes(StandardCharsets.UTF_8));
  }

  // ----------------------------------------------------------------- helpers

  /**
   * Fields a tag inherits from the watch shape without them meaning anything on a tag.
   *
   * <p>A tag and a watch are the same record underneath, so a tag carries the fields a check
   * writes -- when it last ran, what it last saw. Nothing ever checks a tag, so those are
   * dropped from the answer rather than reported as zero, which would read as a real value.
   */
  private static final Set<String> WATCH_ONLY_FIELDS =
      Set.of(
          "browser_steps_last_error_step",
          "check_count",
          "consecutive_filter_failures",
          "content-type",
          "fetch_time",
          "last_changed",
          "last_checked",
          "last_error",
          "last_notification_error",
          "last_viewed",
          "notification_alert_count",
          "page_title",
          "previous_md5",
          "remote_server_reply");

  private String query(String name) {
    return Requests.queryValue(requestContext(), name, "");
  }

  private boolean booleanQuery(String name, boolean fallback) {
    String value = query(name);
    return value.isEmpty() ? fallback : Fields.truthy(value);
  }

  /** Refuses the call when a key is required and the caller did not present the right one. */
  private HttpResponse requireKey(Store store) {
    String presented = Requests.header(requestContext(), "x-api-key");
    if (Guard.apiKeyAccepted(presented, store.application())) {
      return null;
    }
    return Requests.json(StatusCodes.FORBIDDEN, "Invalid access - API key invalid.");
  }

  private static HttpResponse message(akka.http.javadsl.model.StatusCode status, String text) {
    return Requests.json(status, Map.of("message", text));
  }

  /** Strips the keys the service keeps for itself, which are never part of an answer. */
  private static Map<String, Object> stripInternal(Map<String, Object> source) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      if (!entry.getKey().startsWith("__")) {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return out;
  }

  private static Set<String> unknownOf(Set<String> given, Set<String> valid) {
    Set<String> out = new LinkedHashSet<>(given);
    out.removeAll(valid);
    return out;
  }

  /** The fields a caller may not set: the ones the service writes, and the derived ones. */
  private static Set<String> ignoredWatchFields() {
    Set<String> out = new LinkedHashSet<>(ApiSpec.readOnly("Watch"));
    out.addAll(WatchDefaults.SYSTEM_MANAGED);
    out.addAll(List.of("history_n", "last_changed", "viewed", "link"));
    return out;
  }

  private static Map<String, Object> summaryOf(Watch watch, List<String> tags) {
    Map<String, Object> fields = watch.asMap();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("last_changed", watch.lastChanged());
    row.put("last_checked", fields.get("last_checked"));
    row.put("last_error", fields.get("last_error"));
    row.put("link", watch.link(text -> text));
    row.put("page_title", fields.get("page_title"));
    row.put("tags", tags);
    row.put("title", fields.get("title"));
    row.put("url", fields.get("url"));
    row.put("viewed", watch.viewed());
    return row;
  }

  private static boolean matches(Map<String, Object> fields, String needle, boolean partial) {
    String title = String.valueOf(fields.getOrDefault("title", "")).toLowerCase(Locale.ROOT);
    String url = String.valueOf(fields.getOrDefault("url", "")).toLowerCase(Locale.ROOT);
    Object errorValue = fields.get("last_error");
    String error = errorValue == null ? "" : String.valueOf(errorValue).toLowerCase(Locale.ROOT);
    if (partial) {
      return (!title.isEmpty() && title.contains(needle))
          || url.contains(needle)
          || (!error.isEmpty() && error.contains(needle));
    }
    return (!title.isEmpty() && title.equals(needle))
        || url.equals(needle)
        || (!error.isEmpty() && error.equals(needle));
  }

  private static Long asTimestamp(String value) {
    try {
      return Long.parseLong(value.strip());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private List<String> notificationUrls(Store store) {
    Object stored = store.application().get("notification_urls");
    return stringList(stored);
  }

  private void saveNotificationUrls(List<String> urls) {
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateApplication)
        .invoke(new SettingsEntity.UpdateApplication(Map.of("notification_urls", urls)));
  }

  private static List<String> stringList(Object value) {
    List<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
    }
    return out;
  }

  private static Map<String, Object> defaultInterval(Store store) {
    Object stored = store.application().get("time_between_check");
    if (stored instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((key, item) -> out.put(String.valueOf(key), item));
      return out;
    }
    return WatchDefaults.defaultTimeBetweenCheck();
  }

  private static boolean allowFileUri(Store store) {
    return Fields.truthy(store.application().get("allow_file_uri"));
  }

  private String checkProxy(Store store, Object requested) {
    if (requested == null || String.valueOf(requested).isEmpty()) {
      return null;
    }
    List<String> configured = ApiSupport.proxyKeys(store);
    if (configured.contains(String.valueOf(requested))) {
      return null;
    }
    String listed = configured.isEmpty() ? "none configured" : String.join(", ", configured);
    return "Invalid proxy choice, currently supported proxies are '" + listed + "'";
  }

  /**
   * Refuses a watch that opts out of the shared interval without naming one of its own.
   *
   * <p>An empty interval is not the same as no interval: it means every check is due
   * immediately, so a watch saved that way would fetch continuously.
   */
  private static String checkIntervalGiven(Map<String, Object> json) {
    Object useDefault = json.get("time_between_check_use_default");
    if (useDefault == null || Fields.truthy(useDefault)) {
      return null;
    }
    Object interval = json.get("time_between_check");
    String complaint =
        "At least one time interval (weeks, days, hours, minutes, or seconds) must be specified"
            + " when not using global settings.";
    if (!(interval instanceof Map<?, ?> map) || map.isEmpty()) {
      return complaint;
    }
    for (String unit : List.of("weeks", "days", "hours", "minutes", "seconds")) {
      if (ApiSupport.longOf(map.get(unit), 0L) > 0) {
        return null;
      }
    }
    return complaint;
  }

  private static String checkNotificationUrls(Object value) {
    if (value == null) {
      return null;
    }
    return io.akka.changedetection.forms.Checks.notificationUrlProblem(stringList(value));
  }

  private String checkUrl(Object value, Store store) {
    if (value == null) {
      return "URL cannot be null";
    }
    if (!(value instanceof String text)) {
      return "URL must be a string";
    }
    if (text.strip().isEmpty()) {
      return "URL cannot be empty or whitespace only";
    }
    if (!UrlSafety.isSafeValidUrl(text.strip(), allowFileUri(store))) {
      return "Invalid or unsupported URL format. URL must use http://, https://, or ftp://"
          + " protocol";
    }
    return null;
  }

  /**
   * Refuses a tag colour that is not a colour.
   *
   * <p>The value is written into a style block on every page the tag appears on, so anything
   * other than a hex colour is markup somebody else chose.
   */
  private static String checkTagColour(Object value) {
    if (value == null || String.valueOf(value).isEmpty()) {
      return null;
    }
    if (String.valueOf(value).matches("#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?")) {
      return null;
    }
    return "tag_colour: must be a hex colour, for example #4f8ef7";
  }

  /** Pulls the per-processor settings out of a submission; they are stored separately. */
  private static Map<String, Object> extractProcessorConfig(Map<String, Object> json) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : new ArrayList<>(json.keySet())) {
      if (key.startsWith("processor_config_")) {
        out.put(key, json.remove(key));
      }
    }
    return out;
  }

  private static void saveProcessorConfig(
      Store store, String uuid, Map<String, Object> processorConfig) {
    if (processorConfig.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : processorConfig.entrySet()) {
      String kind = entry.getKey();
      try {
        store.saveSideStore(
            uuid, kind, new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of(kind.substring("processor_config_".length()),
                    entry.getValue())));
        store.noteSideStore(uuid, kind);
      } catch (Exception e) {
        // A setting that cannot be written down is not a reason to lose the rest of the watch.
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> restockConfig(Store store, String uuid) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> config = new LinkedHashMap<>();
    String source = "watch";
    String stored = store.sideStore(uuid, "processor_config_restock_diff");
    if (stored != null && !stored.isEmpty()) {
      try {
        Map<String, Object> parsed =
            new com.fasterxml.jackson.databind.ObjectMapper().readValue(stored, Map.class);
        Object inner = parsed.get("restock_diff");
        if (inner instanceof Map<?, ?> map) {
          map.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
      } catch (Exception e) {
        // An unreadable file reads as no settings, which is what the original does with it.
      }
    }
    for (Map.Entry<String, Map<String, Object>> entry : store.tagsForWatch(uuid).entrySet()) {
      if (Fields.truthy(entry.getValue().get("overrides_watch"))) {
        config.clear();
        Object inner = entry.getValue().get("processor_config_restock_diff");
        if (inner instanceof Map<?, ?> map) {
          map.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        source = "tag:" + entry.getKey();
        break;
      }
    }
    out.put("config", config);
    out.put("source", source);
    return out;
  }

  /** Reads one query parameter as the type the published description gives it. */
  private static Object converted(String value, Object property) {
    String type = ApiSpec.typeOf(property);
    switch (type) {
      case "array":
        if (value.startsWith("[")) {
          try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value, List.class);
          } catch (Exception e) {
            // Falls through to the comma-separated reading below.
          }
        }
        List<String> items = new ArrayList<>();
        for (String item : value.split(",")) {
          items.add(item.strip());
        }
        return items;
      case "object":
        try {
          return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value, Map.class);
        } catch (Exception e) {
          throw new IllegalArgumentException("Invalid JSON object for field: " + value);
        }
      case "boolean":
        return Fields.truthy(value);
      case "integer":
        return Long.parseLong(value.strip());
      case "number":
        return Double.parseDouble(value.strip());
      default:
        return value;
    }
  }
}
