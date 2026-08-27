package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchEntity;
import io.akka.changedetection.forms.Choices;
import io.akka.changedetection.forms.Form;
import io.akka.changedetection.forms.Forms;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The main list, and the identifiers behind "select everything matching".
 *
 * <p>The counts beside each filter are tallied over the tag, search and kind the operator has
 * chosen but <em>not</em> over the status toggle, so the numbers stay still while they switch
 * between All, Unread and With errors -- a count that changed as you looked at it would be
 * useless for deciding which to look at.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class WatchListEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public WatchListEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/")
  public HttpResponse index() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/", "watchlist");
    HttpResponse refusal = Guard.requireSignIn(page, "/");
    if (refusal != null) {
      return refusal;
    }

    Map<String, List<String>> arguments = page.query();
    WatchListFilters.View view = WatchListFilters.of(store, arguments);
    String activeTagUuid = view.tagUuid();

    // The old address for the feed was the list with an argument on it; readers that stored
    // it are still out there, so it is answered rather than broken.
    if (!WatchListFilters.first(arguments, "rss").isEmpty()) {
      Map<String, Object> feed = new LinkedHashMap<>();
      if (activeTagUuid != null) {
        feed.put("tag", activeTagUuid);
      }
      return Requests.redirect(Routes.build("rss.feed", feed));
    }

    String operation = WatchListFilters.first(arguments, "op");
    if (!operation.isEmpty()) {
      String uuid = WatchListFilters.first(arguments, "uuid");
      Watch watch = store.watch(uuid).asWatch();
      if (operation.equals("pause")) {
        componentClient
            .forEventSourcedEntity(uuid)
            .method(WatchEntity::setPaused)
            .invoke(new WatchEntity.SetPaused(!Fields.truthy(watch.fields().get("paused"))));
      } else if (operation.equals("mute")) {
        componentClient
            .forEventSourcedEntity(uuid)
            .method(WatchEntity::setMuted)
            .invoke(
                new WatchEntity.SetMuted(
                    !Fields.truthy(watch.fields().get("notification_muted"))));
      }
      Map<String, Object> back = new LinkedHashMap<>();
      if (activeTagUuid != null) {
        back.put("tag", activeTagUuid);
      }
      return Requests.redirect(Routes.build("watchlist.index", back));
    }

    Map<String, Object> application = store.application();
    Environment environment = Render.environmentFor(page, application);

    int erroredCount = 0;
    int dealsCount = 0;
    int unreadCount = 0;
    Map<String, Object> processorCounts = new LinkedHashMap<>();
    List<Watch> selected = new ArrayList<>();

    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      Watch watch = entry.getValue();
      if (!WatchListFilters.matchesTag(store, entry.getKey(), view)) {
        continue;
      }
      if (!WatchListFilters.passesSearch(watch, view)) {
        continue;
      }
      String processor = watch.fields().string("processor", "");
      if (!processor.isEmpty()) {
        Object current = processorCounts.get(processor);
        processorCounts.put(
            processor, (current instanceof Number number ? number.intValue() : 0) + 1);
      }
      if (!view.processor().isEmpty() && !view.processor().equals(processor)) {
        continue;
      }
      if (WatchListFilters.hasError(watch)) {
        erroredCount++;
      }
      if (WatchListFilters.isDeal(watch)) {
        dealsCount++;
      }
      if (!(watch.viewed() || watch.lastChanged() == 0)) {
        unreadCount++;
      }
      if (WatchListFilters.passesStatus(watch, view)) {
        selected.add(watch);
      }
    }

    Map<String, Boolean> checking = new LinkedHashMap<>();
    Map<String, String> checkStatus = new LinkedHashMap<>();
    int checkingNow = 0;
    for (var row : store.watchRows()) {
      checking.put(row.uuid(), row.checking());
      if (row.checking()) {
        checkingNow++;
      }
    }

    boolean hasProxies = new DatastoreView(store).proxies() != null;
    List<Object> watches = new ArrayList<>();
    for (Watch watch : selected) {
      watches.add(
          new WatchView(
              watch,
              environment,
              Boolean.TRUE.equals(checking.get(watch.uuid())),
              checkStatus.get(watch.uuid()),
              hasProxies,
              store.sideStore(watch.uuid(), "favicon-name")));
    }

    String sortAttribute = WatchListFilters.first(arguments, "sort");
    if (sortAttribute.isEmpty()) {
      sortAttribute = Requests.cookie(requestContext(), "sort");
    }
    String sortOrder = WatchListFilters.first(arguments, "order");
    if (sortOrder.isEmpty()) {
      sortOrder = Requests.cookie(requestContext(), "order");
    }

    int pagerSize = Fields.truthy(application.get("pager_size"))
        ? new Fields(Map.of("v", application.get("pager_size"))).integer("v", 50)
        : 50;
    int pageNumber = 1;
    try {
      String requested = WatchListFilters.first(arguments, "page");
      if (!requested.isEmpty()) {
        pageNumber = Integer.parseInt(requested);
      }
    } catch (NumberFormatException e) {
      pageNumber = 1;
    }

    Map<String, String> carried = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : arguments.entrySet()) {
      if (entry.getKey().equals("page") || entry.getValue().isEmpty()) {
        continue;
      }
      carried.put(entry.getKey(), entry.getValue().get(0));
    }
    Pagination pagination =
        new Pagination(
            pageNumber,
            watches.size(),
            pagerSize,
            "/",
            carried,
            page.translate("records"),
            page.translate("displaying <b>{start} - {end}</b> {record_name} in total <b>{total}</b>"));

    List<Object> sortedTags = new ArrayList<>();
    List<Map.Entry<String, Map<String, Object>>> tagEntries =
        new ArrayList<>(store.tags().entrySet());
    tagEntries.sort(
        Comparator.comparing(entry -> String.valueOf(entry.getValue().getOrDefault("title", ""))));
    for (var entry : tagEntries) {
      sortedTags.add(
          new io.akka.changedetection.jinja.PyValue.Tuple(entry.getKey(), entry.getValue()));
    }

    Form quickWatch = Forms.quickWatch(new LinkedHashMap<>(store.tags()));

    boolean llmConfigured =
        io.akka.changedetection.llm.Evaluator.config(store.llmSurroundings()) != null;
    List<String> classes = new ArrayList<>();
    if (Site.queueSize() > 0) {
      classes.add("has-queue");
    }
    if (llmConfigured) {
      classes.add("llm-configured");
    }
    int unreadChanges = store.unreadChangesCount();
    if (unreadChanges > 0) {
      classes.add("has-any-unviewed");
    }

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("active_tag_uuid", activeTagUuid);
    variables.put("active_tag", activeTagUuid == null ? null : store.tags().get(activeTagUuid));
    variables.put("active_processor", view.processor());
    variables.put("any_has_restock_price_processor", 0);
    variables.put("datastore", new DatastoreView(store));
    variables.put("has_proxies", hasProxies ? new DatastoreView(store).proxies() : null);
    variables.put("processor_descriptions", processorDescriptions());
    variables.put("queued_uuids", new ArrayList<>(Site.queued()));
    variables.put("system_default_fetcher", application.get("fetch_backend"));
    variables.put("ui_settings", application.get("ui"));
    variables.put("checking_now_size", checkingNow);
    variables.put("errored_count", erroredCount);
    variables.put("deals_count", dealsCount);
    variables.put("unread_count", unreadCount);
    variables.put("processor_counts", processorCounts);
    variables.put("extra_classes", String.join(" ", classes));
    variables.put("form", quickWatch);
    variables.put("available_fetchers", asTuples(Choices.fetchers()));
    variables.put("hosted_sticky", System.getenv("SALTED_PASS") == null);
    variables.put("now_time_server", System.currentTimeMillis() / 1000);
    variables.put("pagination", pagination);
    variables.put("processor_badge_css", processorBadgeCss());
    variables.put("processor_badge_texts", Choices.processorBadges());
    variables.put("queue_size", Site.queueSize());
    variables.put("active_filters", WatchListFilters.asArguments(arguments));
    variables.put("search_q", WatchListFilters.first(arguments, "q").strip());
    variables.put("sort_attribute", sortAttribute.isEmpty() ? null : sortAttribute);
    variables.put("sort_order", sortOrder.isEmpty() ? null : sortOrder);
    variables.put("tags", sortedTags);
    variables.put("unread_changes_count", unreadChanges);
    variables.put("watches", watches);
    variables.put("llm_configured", llmConfigured);
    variables.put("llm_intent_watch_placeholder", Forms.LLM_INTENT_WATCH_PLACEHOLDER);

    String markup = Render.renderWith(page, environment, "watch-overview.html", variables);
    // The share link is shown once, on the first list page after it was made.
    page.session().withShareLink("");
    HttpResponse response = page.session().attachTo(Requests.html(markup));
    // The table can be ordered from a link or from what was chosen last time; a choice made
    // by link is remembered so the next visit opens the same way.
    if (!WatchListFilters.first(arguments, "sort").isEmpty()) {
      response =
          response.addHeader(
              RawHeader.create(
                  "Set-Cookie",
                  "sort=" + WatchListFilters.first(arguments, "sort") + "; Path=/"));
    }
    if (!WatchListFilters.first(arguments, "order").isEmpty()) {
      response =
          response.addHeader(
              RawHeader.create(
                  "Set-Cookie",
                  "order=" + WatchListFilters.first(arguments, "order") + "; Path=/"));
    }
    return response;
  }

  /** Every watch on the current view, so a selection can span pages. */
  @Get("/uuids")
  public HttpResponse uuids() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/uuids", "watchlist");
    HttpResponse refusal = Guard.requireSignIn(page, "/uuids");
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("uuids", WatchListFilters.matchingUuids(store, page.query()));
    return Requests.json(body);
  }

  static List<Object> asTuples(List<String[]> pairs) {
    List<Object> out = new ArrayList<>();
    for (String[] pair : pairs) {
      out.add(new io.akka.changedetection.jinja.PyValue.Tuple(pair[0], pair[1]));
    }
    return out;
  }

  static Map<String, Object> processorDescriptions() {
    Map<String, Object> descriptions = new LinkedHashMap<>();
    for (String[] processor : Choices.processors()) {
      descriptions.put(processor[0], processor[1]);
    }
    return descriptions;
  }

  /**
   * The colours each kind of watch is badged with, as a stylesheet.
   *
   * <p>Written into the page rather than the shipped stylesheet because the colours are derived
   * from the names, and a deployment can have kinds this build has never seen.
   */
  static String processorBadgeCss() {
    List<String> rules = new ArrayList<>();
    for (String name : Choices.processorNames()) {
      Map<String, Object> colours = Render.badgeColours(name);
      Map<?, ?> light = (Map<?, ?>) colours.get("light");
      Map<?, ?> dark = (Map<?, ?>) colours.get("dark");
      // The doubled class outweighs the browser's own rule for a visited link, which the
      // badge is: without it a badge fades once its own list has been opened.
      rules.add(
          ".processor-badge.processor-badge-"
              + name
              + " {\n  background-color: "
              + light.get("bg")
              + ";\n  color: "
              + light.get("color")
              + ";\n}");
      rules.add(
          "html[data-darkmode=\"true\"] .processor-badge.processor-badge-"
              + name
              + " {\n  background-color: "
              + dark.get("bg")
              + ";\n  color: "
              + dark.get("color")
              + ";\n}");
    }
    return String.join("\n\n", rules);
  }
}
