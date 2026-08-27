package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.model.Watch;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The things the list's own buttons do.
 *
 * <p>Every one of them acts on the list as it is being looked at, not on every watch: an
 * operator who has filtered to one tag and pressed "mark all viewed" means that tag. The
 * filtering is therefore carried on the request and read back here through the same definition
 * the list itself used.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ActionsEndpoint extends AbstractHttpEndpoint {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ComponentClient componentClient;

  public ActionsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/clear_history/{uuid}")
  public HttpResponse clearWatchHistory(String uuid) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(requestContext(), operations.store(), "/clear_history/" + uuid, "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    if (!operations.store().watch(uuid).exists()) {
      page.session().flash("Watch not found", "error");
    } else {
      operations.clearHistory(uuid);
      page.session().flash("Cleared snapshot history for watch " + uuid);
    }
    return page.session().attachTo(Requests.redirect("/"));
  }

  @Get("/clear_history")
  public HttpResponse clearAllHistoryPage() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/clear_history", "ui");
    HttpResponse refusal = Guard.requireSignIn(page, "/clear_history");
    if (refusal != null) {
      return refusal;
    }
    String markup = Render.render(page, "clear_all_history.html", new LinkedHashMap<>());
    return page.session().attachTo(Requests.html(markup));
  }

  @Post("/clear_history")
  public HttpResponse clearAllHistory(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(requestContext(), operations.store(), "/clear_history", "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    String confirmation = submitted.first("confirmtext").strip().toLowerCase(Locale.ROOT);
    // Compared against the translated word, because that is the word the page asked for.
    if (confirmation.equals(page.translate("clear").strip().toLowerCase(Locale.ROOT))) {
      for (String uuid : operations.store().watchUuids()) {
        operations.clearHistory(uuid);
      }
      page.session().flash("History clearing started in background");
    } else {
      page.session().flash("Incorrect confirmation text.", "error");
    }
    return page.session().attachTo(Requests.redirect("/"));
  }

  @Post("/form/mark-all-viewed")
  public HttpResponse markAllViewed() {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/form/mark-all-viewed", "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    WatchListFilters.View view = WatchListFilters.of(store, page.query());
    long now = System.currentTimeMillis() / 1000;
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      if (WatchListFilters.matches(store, entry.getValue(), view)) {
        operations.markViewed(entry.getKey(), now);
      }
    }
    return page.session()
        .attachTo(
            Requests.redirect(
                Routes.build(
                    "watchlist.index", WatchListFilters.asArguments(page.query()))));
  }

  @Post("/delete")
  public HttpResponse delete() {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/delete", "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String uuid = Requests.queryValue(requestContext(), "uuid", "");
    // A watch named 'first' is how the tests reach the only watch there is.
    if (uuid.equals("first")) {
      List<String> uuids = store.watchUuids();
      uuid = uuids.isEmpty() ? "" : uuids.get(uuids.size() - 1);
    }
    if (uuid.equals("all")) {
      operations.deleteAll();
      page.session().flash("Deleted.");
      return page.session().attachTo(Requests.redirect("/"));
    }
    if (!store.watch(uuid).exists()) {
      page.session().flash("The watch by UUID " + uuid + " does not exist.", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    operations.delete(uuid);
    page.session().flash("Deleted.");
    return page.session().attachTo(Requests.redirect("/"));
  }

  @Post("/clone")
  public HttpResponse cloneWatch() {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/clone", "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String uuid = Requests.queryValue(requestContext(), "uuid", "");
    if (uuid.equals("first")) {
      List<String> uuids = store.watchUuids();
      uuid = uuids.isEmpty() ? "" : uuids.get(uuids.size() - 1);
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return page.session().attachTo(Requests.redirect("/"));
    }
    boolean paused = state.asWatch().fields().bool("paused");
    String created = operations.clone(uuid);
    if (created != null && !paused) {
      operations.queueCheck(created);
    }
    page.session().flash("Cloned, you are editing the new watch.");
    return page.session()
        .attachTo(
            Requests.redirect(
                Routes.build("ui.ui_edit.edit_page", Map.of("uuid", created == null ? "" : created))));
  }

  @Post("/checknow")
  public HttpResponse checkNow() {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/checknow", "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }

    String uuid = Requests.queryValue(requestContext(), "uuid", "");
    if (!uuid.isEmpty()) {
      if (isBusy(store, uuid)) {
        page.session().flash("Watch is already queued or being checked.");
      } else {
        operations.queueCheck(uuid);
        page.session().flash("Queued 1 watch for rechecking.");
      }
      return page.session()
          .attachTo(
              Requests.redirect(
                  Routes.build(
                      "watchlist.index", WatchListFilters.asArguments(page.query()))));
    }

    WatchListFilters.View view = WatchListFilters.of(store, page.query());
    // Oldest-checked first, so a queue that cannot drain in one pass still makes progress on
    // the watches that have waited longest.
    List<Map.Entry<String, Watch>> ordered = new ArrayList<>(store.allWatches().entrySet());
    ordered.sort(
        Comparator.comparingLong(entry -> entry.getValue().fields().longValue("last_checked", 0)));

    List<String> wanted = new ArrayList<>();
    for (Map.Entry<String, Watch> entry : ordered) {
      Watch watch = entry.getValue();
      if (!watch.fields().bool("paused") && WatchListFilters.matches(store, watch, view)) {
        wanted.add(entry.getKey());
      }
    }
    List<String> queued = new ArrayList<>();
    for (String candidate : wanted) {
      if (!isBusy(store, candidate)) {
        operations.queueCheck(candidate);
        queued.add(candidate);
      }
    }
    int skipped = wanted.size() - queued.size();
    if (skipped > 0) {
      page.session()
          .flash(
              "Queued " + queued.size() + " watches for rechecking (" + skipped
                  + " already queued or running).");
    } else if (queued.size() == 1) {
      page.session().flash("Queued 1 watch for rechecking.");
    } else {
      page.session().flash("Queued " + queued.size() + " watches for rechecking.");
    }
    return page.session()
        .attachTo(
            Requests.redirect(
                Routes.build(
                    "watchlist.index", WatchListFilters.asArguments(page.query()))));
  }

  @Post("/form/checkbox-operations")
  public HttpResponse checkboxOperations(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(requestContext(), operations.store(), "/form/checkbox-operations", "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    List<String> uuids = new ArrayList<>();
    for (String value : submitted.values().getOrDefault("uuids", List.of())) {
      String trimmed = value.strip();
      if (!trimmed.isEmpty()) {
        uuids.add(trimmed);
      }
    }
    Operations.Outcome outcome =
        operations.apply(submitted.first("op"), uuids, submitted.first("op_extradata").strip());
    if (outcome != null) {
      page.session()
          .flash(
              page.translate(outcome.message()),
              outcome.type().equals("error") ? "error" : "message");
    }
    return page.session().attachTo(Requests.redirect("/"));
  }

  /**
   * Uploads the watch's settings and hands back a link anyone can import it from.
   *
   * <p>The notification settings are stripped before it leaves, along with the identifiers and
   * the check times: a shared watch is meant to be a recipe, and the addresses a person is
   * notified at are not part of one.
   */
  @Get("/share-url/{uuid}")
  public HttpResponse shareWatch(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/share-url/" + uuid, "ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return page.session().attachTo(Requests.redirect("/"));
    }
    Map<String, Object> shared = new LinkedHashMap<>(state.asWatch().asMap());
    shared.remove("history");
    shared.keySet().removeIf(key -> key.startsWith("notification_"));
    for (String key : List.of("uuid", "last_checked", "last_changed")) {
      shared.remove(key);
    }
    Map<String, Object> application = store.application();
    shared.put(
        "ignore_text",
        joined(shared.get("ignore_text"), application.get("global_ignore_text")));
    shared.put(
        "subtractive_selectors",
        joined(
            shared.get("subtractive_selectors"),
            application.get("global_subtractive_selectors")));

    try {
      String json = MAPPER.writeValueAsString(shared);
      String form = "watch=" + java.net.URLEncoder.encode(json, StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://changedetection.io/share/share"))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("App-Guid", String.valueOf(store.settings().settings().getOrDefault("app_guid", "")))
              .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
              .build();
      String replied =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(30))
              .build()
              .send(request, BodyHandlers.ofString(StandardCharsets.UTF_8))
              .body();
      String key = MAPPER.readTree(replied).path("share_key").asText("");
      page.session().withShareLink("https://changedetection.io/share/" + key);
    } catch (Exception e) {
      page.session()
          .flash(
              "Could not share, something went wrong while communicating with the share"
                  + " server - " + e.getMessage(),
              "error");
    }
    return page.session().attachTo(Requests.redirect("/"));
  }

  @Get("/language/auto-detect")
  public HttpResponse autoDetectLanguage() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/language/auto-detect", "ui");
    if (!page.session().locale().isEmpty()) {
      page.session().withLocale("");
      page.session().flash("Language set to auto-detect from browser");
    }
    return page.session()
        .attachTo(
            Requests.redirect(
                AuthEndpoint.safeRedirect(Requests.queryValue(requestContext(), "redirect", ""))));
  }

  @Post("/form/add/quickwatch")
  public HttpResponse quickWatchAdd(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page =
        Render.page(requestContext(), store, "/form/add/quickwatch", "ui.ui_views");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);

    io.akka.changedetection.forms.Form form =
        io.akka.changedetection.forms.Forms.quickWatch(new LinkedHashMap<>(store.tags()));
    form.populate(submitted.values());
    if (!form.validate()) {
      for (Object messages : form.errors().values()) {
        if (messages instanceof List<?> list) {
          for (Object message : list) {
            page.session().flash(String.valueOf(message), "error");
          }
        }
      }
      return page.session().attachTo(Requests.redirect("/"));
    }

    String url = submitted.first("url").strip();
    Map<String, Object> extras = new LinkedHashMap<>();
    String processor = submitted.first("processor").strip();
    if (!processor.isEmpty()) {
      extras.put("processor", processor);
    }
    String created = operations.addWatch(url, submitted.first("tags").strip(), extras);
    if (created == null) {
      page.session().flash("Invalid URL", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    operations.queueCheck(created);
    page.session().flash("Watch added.");

    if (submitted.has("edit_and_watch_submit_button")) {
      Map<String, Object> arguments = new LinkedHashMap<>();
      arguments.put("uuid", created);
      arguments.put("unpause_on_save", 1);
      return page.session()
          .attachTo(Requests.redirect(Routes.build("ui.ui_edit.edit_page", arguments)));
    }
    return page.session()
        .attachTo(
            Requests.redirect(
                Routes.build(
                    "watchlist.index", WatchListFilters.asArguments(page.query()))));
  }

  /** Whether a watch is already on its way, so asking again would only queue it twice. */
  static boolean isBusy(Store store, String uuid) {
    if (Site.queued().contains(uuid)) {
      return true;
    }
    for (var row : store.watchRows()) {
      if (row.uuid().equals(uuid)) {
        return row.checking();
      }
    }
    return false;
  }

  static List<Object> joined(Object own, Object global) {
    List<Object> out = new ArrayList<>();
    if (own instanceof List<?> list) {
      out.addAll(list);
    }
    if (global instanceof List<?> list) {
      out.addAll(list);
    }
    return out;
  }
}
