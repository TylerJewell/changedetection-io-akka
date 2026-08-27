package io.akka.changedetection.web;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Schedule;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.fetchers.Fetcher;
import io.akka.changedetection.fetchers.Fetchers;
import io.akka.changedetection.forms.Choices;
import io.akka.changedetection.forms.Field;
import io.akka.changedetection.forms.Fields;
import io.akka.changedetection.forms.Form;
import io.akka.changedetection.forms.Forms;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.llm.Evaluator;
import io.akka.changedetection.model.Watch;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Editing one watch.
 *
 * <p>The page is built from the same form the submission is read back through, so what the
 * operator sees and what is accepted cannot drift apart. Which controls appear depends on the
 * kind of watch and on what the chosen fetcher can actually do -- offering browser steps to a
 * fetcher that has no browser would be offering something that silently does nothing.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class EditEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public EditEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/edit/{uuid}")
  public HttpResponse editPage(String uuid) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/edit/" + uuid, "ui.ui_edit");
    HttpResponse refusal = Guard.requireSignIn(page, "/edit/" + uuid);
    if (refusal != null) {
      return refusal;
    }

    String resolved = resolve(store, uuid);
    if (store.watchUuids().isEmpty()) {
      page.session().flash("No watches to edit", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    if (!store.watch(resolved).exists()) {
      page.session().flash("No watch with the UUID " + resolved + " found.", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }

    String switchTo = Requests.queryValue(requestContext(), "switch_processor", "");
    if (!switchTo.isEmpty()) {
      for (String[] processor : Choices.processors()) {
        if (processor[0].equals(switchTo)) {
          Map<String, Object> change = new LinkedHashMap<>();
          change.put("processor", switchTo);
          operations.update(resolved, change);
          page.session().flash("Switched to mode - " + processor[1] + ".");
          // A change of kind makes every stored version incomparable with the next one, so the
          // history is cleared rather than left to produce one meaningless difference.
          operations.clearHistory(resolved);
        }
      }
    }

    Watch watch = store.watch(resolved).asWatch();
    Form form = buildForm(store, watch, page);
    form.fill(watch.asMap());
    fillProcessorConfig(store, form, watch, resolved);

    return page.session().attachTo(Requests.html(renderEdit(page, store, resolved, watch, form)));
  }

  @Post("/edit/{uuid}")
  public HttpResponse editSubmit(String uuid, HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/edit/" + uuid, "ui.ui_edit");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String resolved = resolve(store, uuid);
    if (!store.watch(resolved).exists()) {
      page.session().flash("No watch with the UUID " + resolved + " found.", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    Watch watch = store.watch(resolved).asWatch();
    Requests.Submission submitted = Requests.submission(requestContext(), body);

    Form form = buildForm(store, watch, page);
    form.populate(submitted.values());

    if (!form.validate()) {
      page.session().flash("An error occurred, please see below.", "error");
      return page.session()
          .attachTo(Requests.html(renderEdit(page, store, resolved, watch, form)));
    }

    Map<String, Object> data = form.data();
    Map<String, Object> changes = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      if (entry.getKey().startsWith("processor_config_")) {
        continue;
      }
      changes.put(entry.getKey(), entry.getValue());
    }

    changes.put("consecutive_filter_failures", 0);
    changes.put("last_error", false);
    if (!Requests.queryValue(requestContext(), "unpause_on_save", "").isEmpty()) {
      changes.put("paused", false);
    }

    // A submission with none of the three kinds of line ticked means the operator cleared them
    // all, which would report no difference ever; it is read as "back to the default" instead.
    if (data.containsKey("filter_text_added")
        && data.containsKey("filter_text_replaced")
        && data.containsKey("filter_text_removed")
        && !PyValue.truthy(data.get("filter_text_added"))
        && !PyValue.truthy(data.get("filter_text_replaced"))
        && !PyValue.truthy(data.get("filter_text_removed"))) {
      changes.put("filter_text_added", true);
      changes.put("filter_text_replaced", true);
      changes.put("filter_text_removed", true);
    }

    // The group control shows names and submits names; they become identifiers here, which is
    // where a name nobody has used yet becomes a new group.
    Object tags = data.get("tags");
    List<String> tagUuids = new ArrayList<>();
    if (tags instanceof List<?> list) {
      for (Object item : list) {
        tagUuids.add(String.valueOf(item));
      }
    } else if (tags != null && !String.valueOf(tags).isBlank()) {
      for (String name : String.valueOf(tags).split(",")) {
        String tagUuid = operations.addTag(name);
        if (tagUuid != null) {
          tagUuids.add(tagUuid);
        }
      }
    }
    changes.put("tags", tagUuids);

    if (new DatastoreView(store).proxies() != null
        && "".equals(String.valueOf(data.getOrDefault("proxy", "")))) {
      changes.put("proxy", null);
    }

    operations.update(resolved, changes);
    saveProcessorConfig(store, resolved, data, String.valueOf(changes.getOrDefault("processor", "")));

    page.session()
        .flash(
            Requests.queryValue(requestContext(), "unpause_on_save", "").isEmpty()
                ? "Updated watch."
                : "Updated watch - unpaused!");

    Watch saved = store.watch(resolved).asWatch();
    if (!saved.fields().bool("paused") && withinSchedule(store, saved)) {
      operations.queueCheck(resolved);
    }

    if (Requests.queryValue(requestContext(), "next", "").equals("diff")) {
      return page.session()
          .attachTo(
              Requests.redirect(
                  Routes.build("ui.ui_diff.diff_history_page", Map.of("uuid", resolved))));
    }
    Map<String, Object> back = new LinkedHashMap<>();
    back.put("tag", Requests.queryValue(requestContext(), "tag", ""));
    return page.session().attachTo(Requests.redirect(Routes.build("watchlist.index", back)));
  }

  /** The markup of the newest version, offered as a download. */
  @Get("/edit/{uuid}/get-html")
  public HttpResponse latestHtml(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/edit/" + uuid + "/get-html", "ui.ui_edit");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String resolved = resolve(store, uuid);
    WatchState state = store.watch(resolved);
    if (!state.exists() || state.asWatch().history().isEmpty()) {
      return Requests.text(StatusCodes.INTERNAL_SERVER_ERROR, "No stored markup");
    }
    List<Long> history = state.asWatch().history();
    long newest = history.get(history.size() - 1);
    String markup = store.sideStore(resolved, "html-" + newest);
    if (markup == null || markup.isEmpty()) {
      return Requests.text(StatusCodes.INTERNAL_SERVER_ERROR, "No stored markup");
    }
    return Requests.download(
        newest + ".html", ContentTypes.TEXT_HTML_UTF8, markup.getBytes(StandardCharsets.UTF_8));
  }

  /** Everything stored for one watch, in one archive. */
  @Get("/edit/{uuid}/get-data-package")
  public HttpResponse dataPackage(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/edit/" + uuid + "/get-data-package", "ui.ui_edit");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return Requests.notFound();
    }
    Watch watch = state.asWatch();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream archive = new ZipOutputStream(buffer)) {
      archive.putNextEntry(new ZipEntry("watch.json"));
      archive.write(
          io.akka.changedetection.text.PythonJson.dumpsIndented(watch.asMap())
              .getBytes(StandardCharsets.UTF_8));
      archive.closeEntry();
      for (Long timestamp : watch.history()) {
        String snapshot = store.snapshot(uuid, timestamp);
        if (snapshot == null) {
          continue;
        }
        archive.putNextEntry(new ZipEntry(timestamp + ".txt"));
        archive.write(snapshot.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
      }
    } catch (java.io.IOException e) {
      return Requests.text(StatusCodes.INTERNAL_SERVER_ERROR, "Could not build the archive");
    }
    return Requests.download(
        "watch-" + uuid + ".zip", ContentTypes.APPLICATION_OCTET_STREAM, buffer.toByteArray());
  }

  /**
   * Adds a highlighted phrase to a watch's ignore or trigger list.
   *
   * <p>Reached from the difference page, where the operator selects text and says what it
   * means; the phrase is stored as written rather than as an expression.
   */
  @Post("/highlight_submit_ignore_url")
  public HttpResponse highlightSubmit(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page =
        Render.page(requestContext(), store, "/highlight_submit_ignore_url", "ui.ui_edit");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    String uuid = submitted.first("uuid");
    String selection = submitted.first("selection");
    String mode = submitted.first("mode");

    WatchState state = store.watch(uuid);
    if (state.exists() && !selection.strip().isEmpty()) {
      String key =
          switch (mode) {
            case "trigger" -> "trigger_text";
            case "blocked" -> "text_should_not_be_present";
            default -> "ignore_text";
          };
      List<String> lines = new ArrayList<>(state.asWatch().fields().strings(key));
      for (String line : selection.split("\n")) {
        if (!line.strip().isEmpty()) {
          lines.add(line.strip());
        }
      }
      Map<String, Object> change = new LinkedHashMap<>();
      change.put(key, lines);
      operations.update(uuid, change);
    }
    return page.session()
        .attachTo(
            Requests.redirect(
                Routes.build("ui.ui_diff.diff_history_page", Map.of("uuid", uuid))));
  }

  // ------------------------------------------------------------------ shared

  static String resolve(Store store, String uuid) {
    if (!uuid.equals("first")) {
      return uuid;
    }
    List<String> uuids = store.watchUuids();
    return uuids.isEmpty() ? uuid : uuids.get(uuids.size() - 1);
  }

  /** The form for this watch, with the choices that depend on how this instance is set up. */
  Form buildForm(Store store, Watch watch, Render.Page page) {
    String processor = watch.fields().string("processor", Choices.defaultProcessor());
    Form form = Forms.watch(processor, new LinkedHashMap<>(store.tags()), new LinkedHashMap<>());

    Field fetchBackend = form.field("fetch_backend");
    if (fetchBackend instanceof Fields.SelectField select) {
      List<String[]> choices = new ArrayList<>(Choices.fetchers());
      for (Map<String, Object> browser : store.settings().requests().get("extra_browsers")
          instanceof List<?> list ? asMaps(list) : List.<Map<String, Object>>of()) {
        Object name = browser.get("browser_name");
        Object url = browser.get("browser_connection_url");
        if (name == null || url == null || String.valueOf(name).isBlank()) {
          continue;
        }
        choices.add(
            new String[] {"extra_browser_" + name, "Remote browser - " + name});
      }
      choices.add(new String[] {"system", page.translate("System settings default")});
      select.setChoices(choices);
    }

    Map<String, Map<String, String>> proxies = new DatastoreView(store).proxies();
    Field proxy = form.field("proxy");
    if (proxies == null) {
      // No proxies configured means the choice is not offered at all, rather than offered
      // with nothing in it.
      form.fields().remove("proxy");
    } else if (proxy instanceof Fields.SelectField select) {
      List<String[]> choices = new ArrayList<>();
      choices.add(new String[] {"", page.translate("Default")});
      for (Map.Entry<String, Map<String, String>> entry : proxies.entrySet()) {
        choices.add(new String[] {entry.getKey(), entry.getValue().get("label")});
      }
      select.setChoices(choices);
    }
    return form;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> asMaps(List<?> list) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        out.add((Map<String, Object>) map);
      }
    }
    return out;
  }

  /** The settings a kind of watch keeps beside the watch rather than inside it. */
  void fillProcessorConfig(Store store, Form form, Watch watch, String uuid) {
    String processor = watch.fields().string("processor", "");
    if (processor.isEmpty()) {
      return;
    }
    String stored = store.sideStore(uuid, processor + ".json");
    if (stored == null || stored.isEmpty()) {
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> config =
          new com.fasterxml.jackson.databind.ObjectMapper().readValue(stored, Map.class);
      for (Map.Entry<String, Object> entry : config.entrySet()) {
        if (!(entry.getValue() instanceof Map<?, ?> nested)) {
          continue;
        }
        Field target = form.field("processor_config_" + entry.getKey());
        if (target instanceof Form.Nested group) {
          group.setData(nested);
          continue;
        }
        for (Map.Entry<?, ?> pair : nested.entrySet()) {
          Field field = form.field("processor_config_" + pair.getKey());
          if (field != null) {
            field.setData(pair.getValue());
          }
        }
      }
    } catch (Exception e) {
      // Settings that cannot be read leave the controls at their defaults, which is what a
      // watch that has never been configured for this kind shows.
    }
  }

  void saveProcessorConfig(
      Store store, String uuid, Map<String, Object> data, String processor) {
    if (processor == null || processor.isEmpty() || !processor.matches("[A-Za-z0-9_-]+")) {
      return;
    }
    Map<String, Object> config = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      if (!entry.getKey().startsWith("processor_config_")) {
        continue;
      }
      String name = entry.getKey().substring("processor_config_".length());
      if (entry.getValue() instanceof Map<?, ?> nested) {
        config.put(name, nested);
      } else {
        @SuppressWarnings("unchecked")
        Map<String, Object> loose =
            (Map<String, Object>) config.computeIfAbsent(processor, key -> new LinkedHashMap<String, Object>());
        loose.put(name, entry.getValue());
      }
    }
    if (config.isEmpty()) {
      return;
    }
    store.saveSideStore(
        uuid, processor + ".json", io.akka.changedetection.text.PythonJson.dumpsIndented(config));
  }

  static boolean withinSchedule(Store store, Watch watch) {
    Map<String, Object> limit =
        watch.fields().bool("time_between_check_use_default")
            ? mapOf(store.settings().requests().get("time_schedule_limit"))
            : watch.fields().map("time_schedule_limit");
    if (limit == null || !PyValue.truthy(limit.get("enabled"))) {
      return true;
    }
    String zone = String.valueOf(limit.getOrDefault("timezone", ""));
    if (zone.isEmpty() || zone.equals("null")) {
      Object configured = store.application().get("scheduler_timezone_default");
      zone = configured == null ? "" : String.valueOf(configured);
    }
    if (zone.isEmpty() || zone.equals("null")) {
      String fromEnvironment = System.getenv("TZ");
      zone = fromEnvironment == null ? "UTC" : fromEnvironment.strip();
    }
    try {
      return Schedule.isWithin(limit, zone, java.time.ZonedDateTime.now());
    } catch (RuntimeException e) {
      // A timezone that will not resolve leaves the check unscheduled rather than run at the
      // wrong hour.
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> mapOf(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }

  String renderEdit(Render.Page page, Store store, String uuid, Watch watch, Form form) {
    Map<String, Object> application = store.application();
    Environment environment = Render.environmentFor(page, application);

    String processor = watch.fields().string("processor", Choices.defaultProcessor());
    Fetcher fetcher =
        Fetchers.resolve(
            watch.fields().string("fetch_backend", "system"),
            String.valueOf(application.getOrDefault("fetch_backend", "html_requests")),
            false);

    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put("supports_browser_steps", fetcher != null && fetcher.supportsBrowserSteps());
    capabilities.put("supports_screenshots", fetcher != null && fetcher.supportsScreenshots());
    capabilities.put(
        "supports_xpath_element_data", fetcher != null && fetcher.supportsElementPositions());
    capabilities.put("supports_visual_selector", !processor.equals("restock_diff"));
    capabilities.put("supports_text_filters_and_triggers", !processor.equals("image_ssim_diff"));
    capabilities.put(
        "supports_text_filters_and_triggers_elements", !processor.equals("image_ssim_diff"));
    capabilities.put("supports_request_type", processor.equals("text_json_diff"));

    boolean hasProxies = new DatastoreView(store).proxies() != null;
    WatchView view =
        new WatchView(
            watch,
            environment,
            checking(store, uuid),
            null,
            hasProxies,
            store.sideStore(uuid, "favicon-name"));

    Map<String, Object> autoApplied = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : store.tags().entrySet()) {
      if (watch.fields().strings("tags").contains(entry.getKey())) {
        continue;
      }
      if (store.tagsForWatch(uuid).containsKey(entry.getKey())) {
        autoApplied.put(entry.getKey(), entry.getValue());
      }
    }

    Object rssToken = application.get("rss_access_token");
    Map<String, Object> singleFeed = new LinkedHashMap<>();
    singleFeed.put("label", view.label());
    Map<String, Object> feedArguments = new LinkedHashMap<>();
    feedArguments.put("uuid", uuid);
    feedArguments.put("token", rssToken);
    singleFeed.put("url", Routes.build("rss.rss_single_watch", feedArguments));

    List<String> classes = new ArrayList<>();
    classes.add("processor-" + processor);
    if (checking(store, uuid)) {
      classes.add("checking-now");
    }

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("available_processors", WatchListEndpoint.asTuples(Choices.processors()));
    variables.put("available_timezones", Choices.timezones());
    variables.put("browser_steps_config", Choices.browserStepConfig());
    variables.put("emailprefix", envOrFalse("NOTIFICATION_MAIL_BUTTON_PREFIX"));
    variables.put("extra_classes", String.join(" ", classes));
    variables.put("extra_notification_token_placeholder_info", new ArrayList<>());
    variables.put("extra_processor_config", Forms.processorTabLabel(processor));
    variables.put("extra_tab_content", Forms.processorTabLabel(processor));
    variables.put("extra_form_content", null);
    variables.put("extra_title", " - " + page.translate("Edit") + " - " + view.label());
    variables.put("form", form);
    variables.put(
        "has_default_notification_urls",
        application.get("notification_urls") instanceof List<?> urls && !urls.isEmpty());
    variables.put("has_extra_headers_file", !headersFileFor(store, uuid).isEmpty());
    variables.put("has_special_tag_options", tagCarriesFilters(store, watch));
    variables.put("jq_support", true);
    variables.put("playwright_enabled", envOrFalse("PLAYWRIGHT_DRIVER_URL"));
    variables.put("app_rss_token", rssToken);
    variables.put("rss_uuid_feed", singleFeed);
    variables.put("settings_application", application);
    variables.put("ui_edit_stats_extras", new ArrayList<>());
    variables.put("visual_selector_data_ready", visualSelectorReady(store, uuid));
    variables.put("timezone_default_config", application.get("scheduler_timezone_default"));
    variables.put("using_global_webdriver_wait", watch.fields().get("webdriver_delay") == null);
    variables.put("uuid", uuid);
    variables.put("watch", view);
    variables.put("datastore", new DatastoreView(store));
    variables.put("capabilities", capabilities);
    variables.put("auto_applied_tags", autoApplied);
    variables.put(
        "llm_configured", Evaluator.config(store.llmSurroundings()) != null);
    variables.put("llm_group_overrides", groupOverrides(store, watch));

    return Render.renderWith(page, environment, "edit.html", variables);
  }

  /**
   * The value a group supplies for an AI field the watch has left blank.
   *
   * <p>Shown so that the operator can see what will actually be used; the control is drawn
   * read-only in that case, because typing over it here would silently detach the watch from
   * the group rather than change the group.
   */
  static Map<String, Object> groupOverrides(Store store, Watch watch) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : List.of("llm_intent", "llm_change_summary")) {
      result.put(field, null);
      if (!watch.fields().string(field, "").strip().isEmpty()) {
        continue;
      }
      for (String tagUuid : watch.fields().strings("tags")) {
        Map<String, Object> tag = store.tags().get(tagUuid);
        if (tag == null) {
          continue;
        }
        String value = String.valueOf(tag.getOrDefault(field, "")).strip();
        if (value.isEmpty() || value.equals("null")) {
          continue;
        }
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("value", value);
        override.put("group_name", String.valueOf(tag.getOrDefault("title", "tag")));
        result.put(field, override);
        break;
      }
    }
    return result;
  }

  static boolean tagCarriesFilters(Store store, Watch watch) {
    for (String tagUuid : watch.fields().strings("tags")) {
      Map<String, Object> tag = store.tags().get(tagUuid);
      if (tag == null) {
        continue;
      }
      if (notEmpty(tag.get("include_filters")) || notEmpty(tag.get("subtractive_selectors"))) {
        return true;
      }
    }
    return false;
  }

  static boolean notEmpty(Object value) {
    return value instanceof List<?> list && !list.isEmpty();
  }

  static boolean checking(Store store, String uuid) {
    for (var row : store.watchRows()) {
      if (row.uuid().equals(uuid)) {
        return row.checking();
      }
    }
    return false;
  }

  static boolean visualSelectorReady(Store store, String uuid) {
    String elements = store.sideStore(uuid, "elements");
    return elements != null && !elements.isEmpty();
  }

  static String headersFileFor(Store store, String uuid) {
    String stored = store.sideStore(uuid, "headers.txt");
    return stored == null ? "" : stored;
  }

  static Object envOrFalse(String variable) {
    String value = System.getenv(variable);
    return value == null || value.isBlank() ? Boolean.FALSE : value;
  }
}
