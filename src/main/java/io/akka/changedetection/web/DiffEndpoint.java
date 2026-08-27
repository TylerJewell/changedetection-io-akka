package io.akka.changedetection.web;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.diff.DiffRenderer;
import io.akka.changedetection.forms.Form;
import io.akka.changedetection.forms.Forms;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.llm.Evaluator;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.LlmSettings;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.ImageComparison;
import io.akka.changedetection.processors.RestockTimeline;
import io.akka.changedetection.text.PyRegex;
import io.akka.changedetection.text.PythonText;
import io.akka.changedetection.text.SequenceMatcher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * The history of one watch, and everything reached from that page.
 *
 * <p>What "history" means depends on the kind of watch, and the difference is not cosmetic: a
 * price watch stores one short line per check, so a text difference between two of them would
 * say nothing, and a picture watch stores pictures, which have no lines at all. Each kind gets
 * the view that answers the question its operator actually has.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class DiffEndpoint extends AbstractHttpEndpoint {

  /** How many cells the strip above the difference is divided into. */
  private static final int VISUALISER_RESOLUTION = 100;

  private final ComponentClient componentClient;

  public DiffEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/diff/{uuid}")
  public HttpResponse diffPage(String uuid) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/diff/" + uuid, "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignInUnlessShared(page, "/diff/" + uuid);
    if (refusal != null) {
      return refusal;
    }
    String resolved = EditEndpoint.resolve(store, uuid);
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      page.session().flash("No history found for the specified link, bad link?", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    Watch watch = state.asWatch();
    List<Long> dates = watch.history();
    if (dates.size() < 2) {
      page.session()
          .flash(
              "Not enough history (2 snapshots required) to show difference page for this watch.",
              "error");
      return page.session().attachTo(Requests.redirect("/"));
    }

    String processor = watch.fields().string("processor", "text_json_diff");
    return switch (processor) {
      case "restock_diff" -> restockPage(page, operations, resolved, watch, dates);
      case "image_ssim_diff" -> picturePage(page, store, resolved, watch, dates);
      default -> textPage(page, operations, resolved, watch, dates, null);
    };
  }

  // ------------------------------------------------------------------ text

  /** Which two versions a difference page is comparing. */
  record Versions(
      List<Long> dates,
      String from,
      String to,
      String fromContents,
      String toContents,
      boolean viewingLatest,
      String note) {}

  /**
   * The two versions being compared, defaulting to what changed since the operator last looked.
   *
   * <p>Read before the page records that it has now been looked at, because the default depends
   * on the previous value of exactly that.
   */
  Versions resolveVersions(Store store, Watch watch, List<Long> dates, Map<String, List<String>> query) {
    Long bestFrom = watch.fromVersionBasedOnLastViewed();
    String fromDefault =
        bestFrom != null ? String.valueOf(bestFrom) : String.valueOf(dates.get(dates.size() - 2));
    String from = WatchListFilters.first(query, "from_version");
    if (from.isEmpty()) {
      from = fromDefault;
    }
    String to = WatchListFilters.first(query, "to_version");
    if (to.isEmpty()) {
      to = String.valueOf(dates.get(dates.size() - 1));
    }
    String toContents = snapshotOrExcuse(store, watch.uuid(), to, "to-version at ");
    String fromContents = snapshotOrExcuse(store, watch.uuid(), from, "from-version ");
    String note = "";
    if (!from.equals(String.valueOf(dates.get(dates.size() - 2)))
        || !to.equals(String.valueOf(dates.get(dates.size() - 1)))) {
      note = "Note: You are not viewing the latest changes.";
    }
    return new Versions(
        dates,
        from,
        to,
        fromContents,
        toContents,
        to.equals(String.valueOf(dates.get(dates.size() - 1))),
        note);
  }

  private String snapshotOrExcuse(Store store, String uuid, String version, String what) {
    try {
      String contents = store.snapshot(uuid, Long.parseLong(version));
      return contents == null ? "" : contents;
    } catch (RuntimeException e) {
      return "Unable to read " + what + version + ".\n";
    }
  }

  HttpResponse textPage(
      Render.Page page,
      Operations operations,
      String uuid,
      Watch watch,
      List<Long> dates,
      Form extractForm) {
    Store store = operations.store();
    Map<String, Object> application = store.application();
    Environment environment = Render.environmentFor(page, application);
    Map<String, List<String>> query = page.query();

    Versions versions = resolveVersions(store, watch, dates, query);
    operations.markViewed(uuid, System.currentTimeMillis() / 1000);

    // Whether the page carried any of the display settings tells apart "opened fresh" from
    // "submitted with everything unticked", which look identical otherwise.
    boolean submitted =
        query.containsKey("changesOnly")
            || query.containsKey("ignoreWhitespace")
            || query.containsKey("removed")
            || query.containsKey("added")
            || query.containsKey("replaced")
            || query.containsKey("type")
            || query.containsKey("llm_all_changes");

    Map<String, Object> prefs = new LinkedHashMap<>();
    prefs.put("changesOnly", submitted ? flag(query, "changesOnly") : true);
    prefs.put("ignoreWhitespace", submitted ? flag(query, "ignoreWhitespace") : false);
    prefs.put("removed", submitted ? flag(query, "removed") : true);
    prefs.put("added", submitted ? flag(query, "added") : true);
    prefs.put("replaced", submitted ? flag(query, "replaced") : true);
    prefs.put(
        "type",
        submitted && !WatchListFilters.first(query, "type").isEmpty()
            ? WatchListFilters.first(query, "type")
            : "diffLines");
    prefs.put("llm_all_changes", submitted ? flag(query, "llm_all_changes") : false);

    DiffRenderer.Options options = new DiffRenderer.Options();
    options.includeReplaced = PyValueTruthy(prefs.get("replaced"));
    options.includeAdded = PyValueTruthy(prefs.get("added"));
    options.includeRemoved = PyValueTruthy(prefs.get("removed"));
    options.includeEqual = PyValueTruthy(prefs.get("changesOnly"));
    options.ignoreJunk = PyValueTruthy(prefs.get("ignoreWhitespace"));
    options.wordDiff = "diffWords".equals(prefs.get("type"));

    String content =
        DiffRenderer.render(versions.fromContents(), versions.toContents(), options);
    List<Object> cells = visualiser(content);
    content = applyColour(content);

    String offscreen =
        Render.renderWith(page, environment, "diff-offscreen-options.html", new LinkedHashMap<>());

    Map<String, Object> llm = LlmSettings.of(application);
    boolean llmConfigured = !String.valueOf(llm.getOrDefault("model", "")).isEmpty();
    String summary = "";
    String summaryPrompt = "";
    if (llmConfigured) {
      summaryPrompt = Evaluator.effectiveSummaryPrompt(watch, store.llmSurroundings());
      String cachePrompt =
          Evaluator.summaryCachePrompt(
              summaryPrompt,
              (int) asLong(llm.get("max_summary_tokens"), LlmSettings.DEFAULT_MAX_SUMMARY_TOKENS),
              Evaluator.DiffPrefs.standard(),
              String.valueOf(llm.getOrDefault("model", "")));
      summary = cachedSummary(store, uuid, versions.from(), versions.to(), cachePrompt);
    }

    boolean hasProxies = new DatastoreView(store).proxies() != null;
    WatchView view =
        new WatchView(
            watch, environment, false, null, hasProxies, store.sideStore(uuid, "favicon-name"));

    List<String> classes = new ArrayList<>();
    classes.add("difference-page");
    if (llmConfigured) {
      classes.add("llm-configured");
    }

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("bottom_horizontal_offscreen_contents", offscreen);
    variables.put("content", content);
    variables.put("current_diff_url", watch.fields().string("url", ""));
    variables.put("diff_cell_grid", cells);
    variables.put("diff_prefs", prefs);
    variables.put("extra_classes", String.join(" ", classes));
    variables.put(
        "extra_stylesheets",
        List.of(
            Routes.build(
                "static_content", Map.of("group", "styles", "filename", "diff.css"))));
    variables.put("extra_title", " - " + view.label() + " - " + page.translate("History"));
    variables.put(
        "extract_form", extractForm == null ? Forms.extractData() : extractForm);
    variables.put("from_version", versions.from());
    variables.put("is_html_webdriver", supportsScreenshots(store, watch));
    variables.put("last_error", watch.fields().get("last_error"));
    variables.put("last_error_screenshot", errorScreenshot(store, uuid));
    variables.put("last_error_text", store.sideStore(uuid, "last-error.txt"));
    variables.put("newest", versions.toContents());
    variables.put("newest_version_timestamp", dates.get(dates.size() - 1));
    variables.put("note", versions.note());
    variables.put("password_enabled_and_share_is_off", shareIsOff(application));
    variables.put("pure_menu_fixed", false);
    variables.put("screenshot", screenshot(store, uuid));
    variables.put("to_version", versions.to());
    variables.put("uuid", uuid);
    variables.put("versions", dates);
    variables.put("watch_a", view);
    variables.put("watch", view);
    variables.put("datastore", new DatastoreView(store));
    variables.put("llm_configured", llmConfigured);
    variables.put("llm_diff_summary", summary);
    variables.put("llm_summary_prompt", summaryPrompt);
    variables.put("viewing_latest", versions.viewingLatest());

    return page.session()
        .attachTo(Requests.html(Render.renderWith(page, environment, "diff.html", variables)));
  }

  /**
   * A strip showing where in the document the changes fall.
   *
   * <p>Counted over character positions rather than lines, so a change buried in one very long
   * line still shows up in the right place along the strip.
   */
  static List<Object> visualiser(String content) {
    List<Object> cells = new ArrayList<>();
    Map<Integer, String> byCell = new LinkedHashMap<>();
    if (content != null && !content.isEmpty()) {
      double perCell = Math.max(1, content.length() / (double) VISUALISER_RESOLUTION);
      Map<String, String> markers = new LinkedHashMap<>();
      markers.put(DiffRenderer.REMOVED_OPEN, "deletion");
      markers.put(DiffRenderer.ADDED_OPEN, "insertion");
      markers.put(DiffRenderer.CHANGED_OPEN, "deletion");
      markers.put(DiffRenderer.CHANGED_INTO_OPEN, "insertion");
      for (Map.Entry<String, String> marker : markers.entrySet()) {
        int at = content.indexOf(marker.getKey());
        while (at >= 0) {
          int index = Math.min((int) (at / perCell), VISUALISER_RESOLUTION - 1);
          String existing = byCell.get(index);
          if (existing == null) {
            byCell.put(index, marker.getValue());
          } else if (!existing.equals(marker.getValue())) {
            byCell.put(index, "mixed");
          }
          at = content.indexOf(marker.getKey(), at + marker.getKey().length());
        }
      }
    }
    for (int index = 0; index < VISUALISER_RESOLUTION; index++) {
      Map<String, Object> cell = new LinkedHashMap<>();
      cell.put("class", byCell.getOrDefault(index, ""));
      cells.add(cell);
    }
    return cells;
  }

  static String applyColour(String body) {
    String out = body;
    out =
        out.replace(
            DiffRenderer.REMOVED_OPEN,
            "<span style=\"" + DiffRenderer.REMOVED_STYLE
                + "\" role=\"deletion\" aria-label=\"Removed text\" title=\"Removed text\">");
    out = out.replace(DiffRenderer.REMOVED_CLOSED, "</span>");
    out =
        out.replace(
            DiffRenderer.ADDED_OPEN,
            "<span style=\"" + DiffRenderer.ADDED_STYLE
                + "\" role=\"insertion\" aria-label=\"Added text\" title=\"Added text\">");
    out = out.replace(DiffRenderer.ADDED_CLOSED, "</span>");
    out =
        out.replace(
            DiffRenderer.CHANGED_OPEN,
            "<span style=\"" + DiffRenderer.CHANGED_STYLE
                + "\" role=\"note\" aria-label=\"Changed text\" title=\"Changed text\">");
    out = out.replace(DiffRenderer.CHANGED_CLOSED, "</span>");
    out =
        out.replace(
            DiffRenderer.CHANGED_INTO_OPEN,
            "<span style=\"" + DiffRenderer.CHANGED_INTO_STYLE
                + "\" role=\"note\" aria-label=\"Changed into\" title=\"Changed into\">");
    return out.replace(DiffRenderer.CHANGED_INTO_CLOSED, "</span>");
  }

  // --------------------------------------------------------------- restock

  HttpResponse restockPage(
      Render.Page page, Operations operations, String uuid, Watch watch, List<Long> dates) {
    Store store = operations.store();
    Environment environment = Render.environmentFor(page, store.application());
    operations.markViewed(uuid, System.currentTimeMillis() / 1000);

    RestockTimeline.Point latest = null;
    if (!dates.isEmpty()) {
      long newest = dates.get(dates.size() - 1);
      latest = RestockTimeline.parse(newest, store.snapshot(uuid, newest));
    }

    boolean hasProxies = new DatastoreView(store).proxies() != null;
    WatchView view =
        new WatchView(
            watch, environment, false, null, hasProxies, store.sideStore(uuid, "favicon-name"));

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("uuid", uuid);
    variables.put("watch", view);
    variables.put("datastore", new DatastoreView(store));
    variables.put("current_diff_url", watch.fields().string("url", ""));
    variables.put(
        "extra_title", " - " + view.label() + " - " + page.translate("Price history"));
    variables.put("last_error", watch.fields().get("last_error"));
    variables.put("screenshot", screenshot(store, uuid));
    variables.put("last_error_screenshot", errorScreenshot(store, uuid));
    variables.put("last_error_text", store.sideStore(uuid, "last-error.txt"));
    variables.put("versions", dates);
    variables.put(
        "from_version",
        dates.size() >= 2
            ? String.valueOf(dates.get(dates.size() - 2))
            : (dates.isEmpty() ? "" : String.valueOf(dates.get(dates.size() - 1))));
    variables.put(
        "to_version", dates.isEmpty() ? "" : String.valueOf(dates.get(dates.size() - 1)));
    variables.put("restock_latest", latest == null ? null : latest.asMap());
    variables.put("restock_currency", currency(watch));
    variables.put("has_enough_history", dates.size() >= 2);
    variables.put(
        "processor_data_url",
        Routes.build("ui.ui_diff.diff_history_page_processor_data", Map.of("uuid", uuid)));

    return page.session()
        .attachTo(
            Requests.html(
                Render.renderWith(page, environment, "restock_diff/difference.html", variables)));
  }

  // --------------------------------------------------------------- picture

  HttpResponse picturePage(
      Render.Page page, Store store, String uuid, Watch watch, List<Long> dates) {
    Environment environment = Render.environmentFor(page, store.application());
    Map<String, List<String>> query = page.query();

    String from = chosenVersion(query, "from_version", dates, dates.size() - 2);
    String to = chosenVersion(query, "to_version", dates, dates.size() - 1);
    double sensitivity = sensitivity(store, watch);

    double changed = 0;
    try {
      byte[] before = decode(store.snapshot(uuid, Long.parseLong(from)));
      byte[] after = decode(store.snapshot(uuid, Long.parseLong(to)));
      changed =
          ImageComparison.changePercentage(
              before, after, sensitivity, ImageComparison.DEFAULT_BLUR_SIGMA, null);
    } catch (RuntimeException e) {
      page.session().flash("Failed to load screenshots: " + e.getMessage(), "error");
      return page.session().attachTo(Requests.redirect("/"));
    }

    boolean hasProxies = new DatastoreView(store).proxies() != null;
    WatchView view =
        new WatchView(
            watch, environment, false, null, hasProxies, store.sideStore(uuid, "favicon-name"));

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("change_percentage", changed);
    variables.put("comparison_data", new ArrayList<>());
    variables.put("comparison_method", "Pixel difference");
    variables.put("current_diff_url", watch.fields().string("url", ""));
    variables.put("from_version", from);
    variables.put("percentage_different", changed);
    variables.put("threshold", sensitivity);
    variables.put("to_version", to);
    variables.put("uuid", uuid);
    variables.put("versions", dates);
    variables.put("watch", view);
    variables.put("datastore", new DatastoreView(store));

    return page.session()
        .attachTo(
            Requests.html(
                Render.renderWith(page, environment, "image_ssim_diff/diff.html", variables)));
  }

  @Get("/diff/{uuid}/processor-asset/{assetName}")
  public HttpResponse processorAsset(String uuid, String assetName) {
    return asset(uuid, assetName, "/diff/" + uuid + "/processor-asset/" + assetName);
  }

  HttpResponse asset(String uuid, String assetName, String path) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, path, "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignInUnlessShared(page, path);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return Requests.notFound();
    }
    Watch watch = state.asWatch();
    List<Long> dates = watch.history();
    if (dates.size() < 2) {
      return Requests.notFound();
    }
    Map<String, List<String>> query = page.query();
    String from = chosenVersion(query, "from_version", dates, dates.size() - 2);
    String to = chosenVersion(query, "to_version", dates, dates.size() - 1);

    byte[] before = decode(store.snapshot(uuid, Long.parseLong(from)));
    byte[] after = decode(store.snapshot(uuid, Long.parseLong(to)));

    byte[] body;
    ContentType type;
    switch (assetName) {
      case "before" -> {
        body = before;
        type = pictureType(before);
      }
      case "after" -> {
        body = after;
        type = pictureType(after);
      }
      case "rendered_diff" -> {
        body =
            ImageComparison.renderedDifference(
                before, after, sensitivity(store, watch), ImageComparison.DEFAULT_BLUR_SIGMA);
        type = ContentTypes.create(MediaTypes.IMAGE_JPEG);
      }
      default -> {
        return Requests.notFound();
      }
    }
    if (body == null || body.length == 0) {
      return Requests.notFound();
    }
    return Requests.bytes(StatusCodes.OK, type, body)
        .addHeader(RawHeader.create("Cache-Control", "public, max-age=3600"));
  }

  // ------------------------------------------------------------- side data

  @Get("/diff/{uuid}/processor-data")
  public HttpResponse processorData(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/diff/" + uuid + "/processor-data", "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String resolved = EditEndpoint.resolve(store, uuid);
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      return Requests.json(StatusCodes.NOT_FOUND, Map.of("error", "Watch not found"));
    }
    Watch watch = state.asWatch();
    if (!watch.fields().string("processor", "").equals("restock_diff")) {
      return Requests.text(
          StatusCodes.NOT_FOUND,
          "Processor '" + watch.fields().string("processor", "") + "' does not provide"
              + " difference data");
    }
    List<RestockTimeline.Point> series = seriesFor(store, watch);
    List<Object> asMaps = new ArrayList<>();
    for (RestockTimeline.Point point : series) {
      asMaps.add(point.asMap());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("series", asMaps);
    body.put("currency", currency(watch));
    body.put("summary", RestockTimeline.priceSummary(series));
    return Requests.json(body);
  }

  @Get("/diff/{uuid}/processor-export.xlsx")
  public HttpResponse processorExport(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(
            requestContext(), store, "/diff/" + uuid + "/processor-export.xlsx", "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String resolved = EditEndpoint.resolve(store, uuid);
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      page.session().flash("No history found for the specified link, bad link?", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    Watch watch = state.asWatch();
    if (!watch.fields().string("processor", "").equals("restock_diff")) {
      return Requests.text(
          StatusCodes.NOT_FOUND,
          "Processor '" + watch.fields().string("processor", "") + "' does not support xlsx"
              + " export");
    }
    String currency = currency(watch);
    Xlsx workbook =
        new Xlsx("Price history")
            .heading(
                List.of(
                    page.translate("Date"),
                    page.translate("Stock status"),
                    page.translate("Price"),
                    page.translate("Currency")))
            .widths(List.of(20, 14, 12, 10));
    for (RestockTimeline.Point point : seriesFor(store, watch)) {
      String stock =
          point.inStock() == null
              ? ""
              : (point.inStock() ? page.translate("In stock") : page.translate("Out of stock"));
      workbook.row(
          List.of(
              new Xlsx.Moment(point.timestamp()),
              new Xlsx.Words(stock),
              new Xlsx.Number(point.price()),
              new Xlsx.Words(currency)));
    }
    return Requests.download(
        "price-history-" + resolved + ".xlsx",
        ContentTypes.create(
            MediaTypes.applicationBinary(
                "vnd.openxmlformats-officedocument.spreadsheetml.sheet", false)),
        workbook.bytes());
  }

  // ------------------------------------------------------------- extracting

  @Get("/diff/{uuid}/extract")
  public HttpResponse extractForm(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/diff/" + uuid + "/extract", "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignIn(page, "/diff/" + uuid + "/extract");
    if (refusal != null) {
      return refusal;
    }
    String resolved = EditEndpoint.resolve(store, uuid);
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      page.session().flash("No history found for the specified link, bad link?", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    return page.session()
        .attachTo(
            Requests.html(
                renderExtract(page, store, resolved, state.asWatch(), Forms.extractData())));
  }

  @Post("/diff/{uuid}/extract")
  public HttpResponse extract(String uuid, HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/diff/" + uuid + "/extract", "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String resolved = EditEndpoint.resolve(store, uuid);
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      page.session().flash("No history found for the specified link, bad link?", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    Watch watch = state.asWatch();
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    Form form = Forms.extractData();
    form.populate(submitted.values());
    if (!form.validate()) {
      page.session().flash("An error occurred, please see below.", "error");
      return page.session()
          .attachTo(Requests.html(renderExtract(page, store, resolved, watch, form)));
    }

    String expression = submitted.first("extract_regex").strip();
    String csv = extractAllHistory(store, watch, expression);
    if (csv == null) {
      page.session()
          .flash(
              "No matches found while scanning all of the watch history for that RegEx.", "error");
      return page.session()
          .attachTo(
              Requests.redirect(
                  Routes.build(
                      "ui.ui_diff.diff_history_page_extract_GET", Map.of("uuid", resolved))));
    }
    return page.session()
        .attachTo(
            Requests.download(
                    "report-" + resolved + ".csv",
                    ContentTypes.create(
                        MediaTypes.TEXT_CSV, akka.http.javadsl.model.HttpCharsets.UTF_8),
                    csv.getBytes(StandardCharsets.UTF_8))
                .addHeader(RawHeader.create("Cache-Control", "no-cache, no-store, must-revalidate"))
                .addHeader(RawHeader.create("Pragma", "no-cache"))
                .addHeader(RawHeader.create("Expires", "0")));
  }

  /**
   * Every match of an expression across the whole history, as a spreadsheet-readable file.
   *
   * @return null when nothing matched anywhere, which the page reports rather than downloading
   *     an empty file
   */
  static String extractAllHistory(Store store, Watch watch, String expression) {
    java.util.regex.Pattern pattern;
    try {
      pattern = PyRegex.compile(expression);
    } catch (RuntimeException e) {
      return null;
    }
    StringBuilder csv = new StringBuilder();
    boolean any = false;
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    for (Long timestamp : watch.history()) {
      String contents = store.snapshot(watch.uuid(), timestamp);
      if (contents == null) {
        continue;
      }
      Matcher matcher = pattern.matcher(contents);
      String when =
          LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault())
              .format(formatter);
      while (matcher.find()) {
        if (!any) {
          csv.append("Epoch seconds,Date\r\n");
          any = true;
        }
        List<String> row = new ArrayList<>();
        row.add(String.valueOf(timestamp));
        row.add(when);
        if (matcher.groupCount() == 0) {
          row.add(matcher.group(0));
        } else {
          for (int group = 1; group <= matcher.groupCount(); group++) {
            row.add(matcher.group(group) == null ? "" : matcher.group(group));
          }
        }
        csv.append(csvRow(row)).append("\r\n");
      }
    }
    return any ? csv.toString() : null;
  }

  static String csvRow(List<String> values) {
    List<String> quoted = new ArrayList<>();
    for (String value : values) {
      if (value.contains(",") || value.contains("\"") || value.contains("\n")
          || value.contains("\r")) {
        quoted.add("\"" + value.replace("\"", "\"\"") + "\"");
      } else {
        quoted.add(value);
      }
    }
    return String.join(",", quoted);
  }

  String renderExtract(Render.Page page, Store store, String uuid, Watch watch, Form form) {
    Environment environment = Render.environmentFor(page, store.application());
    boolean hasProxies = new DatastoreView(store).proxies() != null;
    WatchView view =
        new WatchView(
            watch, environment, false, null, hasProxies, store.sideStore(uuid, "favicon-name"));
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("uuid", uuid);
    variables.put("extract_form", form);
    variables.put("watch_a", view);
    variables.put("watch", view);
    variables.put("datastore", new DatastoreView(store));
    variables.put("last_error", watch.fields().get("last_error"));
    variables.put("last_error_screenshot", errorScreenshot(store, uuid));
    variables.put("last_error_text", store.sideStore(uuid, "last-error.txt"));
    variables.put("screenshot", screenshot(store, uuid));
    variables.put("is_html_webdriver", supportsScreenshots(store, watch));
    variables.put("password_enabled_and_share_is_off", shareIsOff(store.application()));
    variables.put(
        "extra_title", " - " + view.label() + " - " + page.translate("Extract Data"));
    variables.put(
        "extra_stylesheets",
        List.of(
            Routes.build("static_content", Map.of("group", "styles", "filename", "diff.css"))));
    variables.put("pure_menu_fixed", false);
    return Render.renderWith(page, environment, "extract.html", variables);
  }

  // -------------------------------------------------------------- the patch

  @Get("/diff/{uuid}/download-patch")
  public HttpResponse downloadPatch(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/diff/" + uuid + "/download-patch", "ui.ui_diff");
    HttpResponse refusal =
        Guard.requireSignInUnlessShared(page, "/diff/" + uuid + "/download-patch");
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return Requests.text(StatusCodes.NOT_FOUND, "Watch not found");
    }
    Watch watch = state.asWatch();
    List<Long> dates = watch.history();
    if (dates.size() < 2) {
      return Requests.text(StatusCodes.BAD_REQUEST, "Not enough history");
    }
    Map<String, List<String>> query = page.query();
    String from = chosenVersion(query, "from_version", dates, dates.size() - 2);
    String to = chosenVersion(query, "to_version", dates, dates.size() - 1);

    String fromText = store.snapshot(uuid, Long.parseLong(from));
    String toText = store.snapshot(uuid, Long.parseLong(to));
    if (fromText == null || toText == null) {
      return Requests.text(StatusCodes.INTERNAL_SERVER_ERROR, "Could not read snapshots");
    }
    List<String> lines =
        SequenceMatcher.unifiedDiff(
            PythonText.splitLinesKeepEnds(fromText),
            PythonText.splitLinesKeepEnds(toText),
            "snapshot-" + from,
            "snapshot-" + to,
            "",
            "",
            3,
            "");
    String patch = lines.isEmpty() ? "(no differences)\n" : String.join("", lines);
    return Requests.text(patch);
  }

  // ------------------------------------------------------------ AI summary

  @Get("/diff/{uuid}/llm-summary/prompt")
  public HttpResponse summaryPrompt(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(
            requestContext(), store, "/diff/" + uuid + "/llm-summary/prompt", "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return Requests.json(StatusCodes.NOT_FOUND, Map.of("prompt", ""));
    }
    String prompt;
    try {
      prompt = Evaluator.effectiveSummaryPrompt(state.asWatch(), store.llmSurroundings());
    } catch (RuntimeException e) {
      prompt = "";
    }
    return Requests.json(Map.of("prompt", prompt));
  }

  @Get("/diff/{uuid}/llm-summary")
  public HttpResponse summary(String uuid) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page =
        Render.page(requestContext(), store, "/diff/" + uuid + "/llm-summary", "ui.ui_diff");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return Requests.json(
          StatusCodes.NOT_FOUND, mapOf("summary", null, "error", "Watch not found"));
    }
    Watch watch = state.asWatch();
    Map<String, Object> llm = LlmSettings.of(store.application());
    if (String.valueOf(llm.getOrDefault("model", "")).isEmpty()) {
      return Requests.json(
          StatusCodes.BAD_REQUEST, mapOf("summary", null, "error", "LLM not configured"));
    }
    List<Long> dates = watch.history();
    if (dates.size() < 2) {
      return Requests.json(
          StatusCodes.BAD_REQUEST, mapOf("summary", null, "error", "Not enough history"));
    }

    Map<String, List<String>> query = page.query();
    String defaultFrom;
    if ("since_last_viewed".equals(llm.get("watchlist_overview_summary"))) {
      Long best = watch.fromVersionBasedOnLastViewed();
      defaultFrom =
          best != null ? String.valueOf(best) : String.valueOf(dates.get(dates.size() - 2));
    } else {
      defaultFrom = String.valueOf(dates.get(dates.size() - 2));
    }
    String from = WatchListFilters.first(query, "from_version");
    if (from.isEmpty()) {
      from = defaultFrom;
    }
    String to = WatchListFilters.first(query, "to_version");
    if (to.isEmpty()) {
      to = String.valueOf(dates.get(dates.size() - 1));
    }

    Evaluator.DiffPrefs prefs =
        new Evaluator.DiffPrefs(
            "1".equals(WatchListFilters.first(query, "all_changes")),
            "1".equals(WatchListFilters.first(query, "ignore_whitespace")),
            !"0".equals(WatchListFilters.first(query, "removed")),
            !"0".equals(WatchListFilters.first(query, "added")));

    String fromText = store.snapshot(uuid, Long.parseLong(from));
    String toText = store.snapshot(uuid, Long.parseLong(to));
    if (fromText == null || toText == null) {
      return Requests.json(
          StatusCodes.INTERNAL_SERVER_ERROR,
          mapOf("summary", null, "error", "Could not read snapshots"));
    }

    String difference;
    if (prefs.allChanges()) {
      // Every step in turn rather than first against last, so the model sees the sequence of
      // changes and not just the net effect of them.
      List<Long> ordered = new ArrayList<>(dates);
      java.util.Collections.sort(ordered);
      int start = ordered.indexOf(Long.parseLong(from));
      int end = ordered.indexOf(Long.parseLong(to));
      if (start < 0 || end < 0) {
        start = 0;
        end = ordered.size() - 1;
      }
      List<String> segments = new ArrayList<>();
      for (int index = start; index < end; index++) {
        String before = store.snapshot(uuid, ordered.get(index));
        String after = store.snapshot(uuid, ordered.get(index + 1));
        String segment =
            filtered(unified(before == null ? "" : before, after == null ? "" : after, prefs), prefs);
        if (!segment.strip().isEmpty()) {
          segments.add(
              "=== " + ordered.get(index) + " → " + ordered.get(index + 1) + " ===\n" + segment);
        }
      }
      difference = String.join("\n\n", segments);
    } else {
      difference = filtered(unified(fromText, toText, prefs), prefs);
    }

    if (difference.strip().isEmpty()) {
      return Requests.json(mapOf("summary", null, "error", "No differences found"));
    }

    String cachePrompt =
        Evaluator.summaryCachePrompt(
            Evaluator.effectiveSummaryPrompt(watch, store.llmSurroundings()),
            (int) asLong(llm.get("max_summary_tokens"), LlmSettings.DEFAULT_MAX_SUMMARY_TOKENS),
            prefs,
            String.valueOf(llm.getOrDefault("model", "")));
    String cached = cachedSummary(store, uuid, from, to, cachePrompt);
    if (!cached.isEmpty()) {
      operations.markViewed(uuid, System.currentTimeMillis() / 1000);
      Map<String, Object> body = mapOf("summary", cached, "error", null);
      body.put("cached", true);
      return Requests.json(body);
    }

    if (Evaluator.globalBudgetExceeded(store.llmSurroundings())) {
      long budget = Evaluator.globalTokenBudget(store.llmSurroundings());
      long used = asLong(llm.get("tokens_this_month"), 0);
      Map<String, Object> body =
          mapOf(
              "summary",
              null,
              "error",
              "Monthly AI token budget of "
                  + String.format(Locale.US, "%,d", budget)
                  + " tokens reached ("
                  + String.format(Locale.US, "%,d", used)
                  + " used). Resets next month.");
      body.put("budget_exceeded", true);
      return Requests.json(StatusCodes.TOO_MANY_REQUESTS, body);
    }

    String produced;
    try {
      produced = Evaluator.summariseChange(watch, store.llmSurroundings(), difference, toText);
    } catch (Evaluator.InputTooLarge e) {
      return Requests.json(
          StatusCodes.BAD_REQUEST, mapOf("summary", null, "error", e.getMessage()));
    } catch (RuntimeException e) {
      return Requests.json(
          StatusCodes.INTERNAL_SERVER_ERROR,
          mapOf("summary", null, "error", e.getMessage()));
    }
    if (produced == null || produced.isEmpty()) {
      return Requests.json(mapOf("summary", null, "error", "LLM returned empty summary"));
    }
    store.saveSideStore(uuid, summaryKey(from, to, cachePrompt), produced);
    operations.markViewed(uuid, System.currentTimeMillis() / 1000);
    Map<String, Object> body = mapOf("summary", produced, "error", null);
    body.put("cached", false);
    return Requests.json(body);
  }

  static String unified(String before, String after, Evaluator.DiffPrefs prefs) {
    List<String> a = prepared(before, prefs);
    List<String> b = prepared(after, prefs);
    List<String> lines = SequenceMatcher.unifiedDiff(a, b, "", "", "", "", 3, "");
    if (lines.size() > 2) {
      return String.join("\n", lines.subList(2, lines.size()));
    }
    return String.join("\n", lines);
  }

  static List<String> prepared(String text, Evaluator.DiffPrefs prefs) {
    List<String> out = new ArrayList<>();
    for (String line : PythonText.splitLines(text)) {
      out.add(prefs.ignoreWhitespace() ? String.join(" ", line.trim().split("\\s+")) : line);
    }
    return out;
  }

  /** Drops the kinds of line the operator has hidden, so the model sees what they see. */
  static String filtered(String difference, Evaluator.DiffPrefs prefs) {
    if (prefs.showRemoved() && prefs.showAdded()) {
      return difference;
    }
    List<String> kept = new ArrayList<>();
    for (String line : PythonText.splitLines(difference)) {
      if (line.startsWith("-") && !prefs.showRemoved()) {
        continue;
      }
      if (line.startsWith("+") && !prefs.showAdded()) {
        continue;
      }
      kept.add(line);
    }
    return String.join("\n", kept);
  }

  static String summaryKey(String from, String to, String prompt) {
    return "change-summary-" + from + "-to-" + to + "-"
        + Evaluator.hexDigest("MD5", prompt).substring(0, 8);
  }

  static String cachedSummary(Store store, String uuid, String from, String to, String prompt) {
    String stored = store.sideStore(uuid, summaryKey(from, to, prompt));
    return stored == null ? "" : stored.strip();
  }

  // ------------------------------------------------------------------ bits

  static boolean flag(Map<String, List<String>> query, String name) {
    String value = WatchListFilters.first(query, name);
    if (value.isEmpty()) {
      return false;
    }
    String lower = value.strip().toLowerCase(Locale.ROOT);
    return lower.equals("y")
        || lower.equals("yes")
        || lower.equals("t")
        || lower.equals("true")
        || lower.equals("on")
        || lower.equals("1");
  }

  static boolean PyValueTruthy(Object value) {
    return Fields.truthy(value);
  }

  static String chosenVersion(
      Map<String, List<String>> query, String name, List<Long> dates, int fallbackIndex) {
    String requested = WatchListFilters.first(query, name);
    int index = Math.max(0, Math.min(fallbackIndex, dates.size() - 1));
    String fallback = String.valueOf(dates.get(index));
    if (requested.isEmpty()) {
      return fallback;
    }
    for (Long date : dates) {
      if (String.valueOf(date).equals(requested)) {
        return requested;
      }
    }
    return fallback;
  }

  static double sensitivity(Store store, Watch watch) {
    Object own = watch.fields().get("pixel_difference_threshold_sensitivity");
    Object configured =
        own == null || String.valueOf(own).isEmpty()
            ? store.application().get("pixel_difference_threshold_sensitivity")
            : own;
    if (configured == null) {
      return ImageComparison.DEFAULT_PIXEL_THRESHOLD;
    }
    try {
      return Double.parseDouble(String.valueOf(configured));
    } catch (NumberFormatException e) {
      return 30.0;
    }
  }

  static List<RestockTimeline.Point> seriesFor(Store store, Watch watch) {
    List<RestockTimeline.Point> series = new ArrayList<>();
    for (Long timestamp : watch.history()) {
      series.add(RestockTimeline.parse(timestamp, store.snapshot(watch.uuid(), timestamp)));
    }
    return series;
  }

  static String currency(Watch watch) {
    Map<String, Object> restock = watch.fields().map("restock");
    if (restock == null) {
      return "";
    }
    Object currency = restock.get("currency");
    return currency == null ? "" : String.valueOf(currency);
  }

  static boolean supportsScreenshots(Store store, Watch watch) {
    var fetcher =
        io.akka.changedetection.fetchers.Fetchers.resolve(
            watch.fields().string("fetch_backend", "system"),
            String.valueOf(store.application().getOrDefault("fetch_backend", "html_requests")),
            false);
    return fetcher != null && fetcher.supportsScreenshots();
  }

  static boolean shareIsOff(Map<String, Object> application) {
    boolean passwordSet = Render.hasPassword(application);
    return passwordSet && !Fields.truthy(application.get("shared_diff_access"));
  }

  static Object screenshot(Store store, String uuid) {
    String stored = store.sideStore(uuid, "last-screenshot.png");
    return stored == null || stored.isEmpty()
        ? Boolean.FALSE
        : Routes.build("static_content", Map.of("group", "screenshot", "filename", uuid));
  }

  static Object errorScreenshot(Store store, String uuid) {
    String stored = store.sideStore(uuid, "last-error-screenshot.png");
    if (stored == null || stored.isEmpty()) {
      return Boolean.FALSE;
    }
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("group", "screenshot");
    arguments.put("filename", uuid);
    arguments.put("error_screenshot", 1);
    return Routes.build("static_content", arguments);
  }

  static byte[] decode(String stored) {
    if (stored == null || stored.isEmpty()) {
      return new byte[0];
    }
    try {
      return Base64.getDecoder().decode(stored);
    } catch (IllegalArgumentException e) {
      return stored.getBytes(StandardCharsets.UTF_8);
    }
  }

  static ContentType pictureType(byte[] body) {
    if (body.length > 3
        && (body[0] & 0xFF) == 0x89
        && body[1] == 'P'
        && body[2] == 'N'
        && body[3] == 'G') {
      return ContentTypes.create(MediaTypes.IMAGE_PNG);
    }
    if (body.length > 1 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8) {
      return ContentTypes.create(MediaTypes.IMAGE_JPEG);
    }
    return ContentTypes.APPLICATION_OCTET_STREAM;
  }

  static long asLong(Object value, long fallback) {
    return value instanceof java.lang.Number number ? number.longValue() : fallback;
  }

  static Map<String, Object> mapOf(String firstKey, Object firstValue, String secondKey,
      Object secondValue) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(firstKey, firstValue);
    out.put(secondKey, secondValue);
    return out;
  }
}
