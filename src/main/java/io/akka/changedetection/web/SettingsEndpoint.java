package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.SettingsEntity;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.forms.Choices;
import io.akka.changedetection.forms.Form;
import io.akka.changedetection.forms.Forms;
import io.akka.changedetection.llm.Evaluator;
import io.akka.changedetection.llm.ProviderProbe;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.LlmSettings;
import io.akka.changedetection.model.Watch;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * The settings that apply to everything, and the switches beside them.
 *
 * <p>Three things about this page are not obvious and each has a reason. The notification
 * fields live on their own page, so a save here deliberately leaves them alone -- the form
 * still declares them, inherited from the shared shape, and merging its empty defaults would
 * silently wipe what the other page stored. The provider key is never rendered back, so an
 * empty submission means "keep what is stored" rather than "clear it". And a save clears every
 * watch's stored body checksum, because a global filter change has to take effect on the next
 * check rather than the next time a page happens to move.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class SettingsEndpoint extends AbstractHttpEndpoint {

  /** Fields the settings page declares but does not own. */
  private static final List<String> NOTIFICATION_FIELDS =
      List.of(
          "notification_urls",
          "notification_title",
          "notification_body",
          "notification_format",
          "base_url");

  private final ComponentClient componentClient;

  public SettingsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/settings")
  public HttpResponse settingsPage() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/settings", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, "/settings");
    if (refusal != null) {
      return refusal;
    }
    Form form = buildForm(store);
    form.fill(defaults(store));
    return page.session().attachTo(Requests.html(renderPage(page, store, form)));
  }

  @Post("/settings")
  public HttpResponse settingsSubmit(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/settings", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    Form form = buildForm(store);
    form.populate(submitted.values());

    // Removing the password is its own button rather than an empty field, because an empty
    // field is what a browser sends for a password it did not re-render.
    if (submitted.values().containsKey("application-removepassword_button")) {
      if (System.getenv("SALTED_PASS") == null || System.getenv("SALTED_PASS").isBlank()) {
        componentClient
            .forKeyValueEntity(SettingsEntity.ID)
            .method(SettingsEntity::updateApplication)
            .invoke(new SettingsEntity.UpdateApplication(Map.of("password", false)));
        page.session().flash("Password protection removed.", "notice");
        page.session().signedIn(false);
        return page.session().attachTo(Requests.redirect("/settings"));
      }
    }

    if (!form.validate()) {
      page.session().flash("An error occurred, please see below.", "error");
      return page.session().attachTo(Requests.html(renderPage(page, store, form)));
    }

    Map<String, Object> data = form.data();
    Map<String, Object> application = new LinkedHashMap<>(asMap(data.get("application")));
    // The field never carries the password itself, only what was derived from it -- an empty
    // submission is a browser not re-rendering a password, never a request to clear one.
    application.remove("password");
    String password =
        ((io.akka.changedetection.forms.SpecialFields.SaltedPasswordField)
                ((Form.Nested) form.field("application")).inner().field("password"))
            .derived();
    NOTIFICATION_FIELDS.forEach(application::remove);
    application.remove("removepassword_button");

    Map<String, Object> merged = mergedLlm(store, asMap(data.get("llm")));
    application.put("llm", merged);

    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateApplication)
        .invoke(new SettingsEntity.UpdateApplication(application));
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateRequests)
        .invoke(new SettingsEntity.UpdateRequests(asMap(data.get("requests"))));

    // Every stored body checksum is forgotten, because a global rule change has to be applied
    // to the next fetch rather than waiting for the watched page to move.
    clearAllChecksums(store);

    boolean salted = System.getenv("SALTED_PASS") != null && !System.getenv("SALTED_PASS").isBlank();
    if (!salted && !password.isEmpty()) {
      componentClient
          .forKeyValueEntity(SettingsEntity.ID)
          .method(SettingsEntity::updateApplication)
          .invoke(
              new SettingsEntity.UpdateApplication(
                  Map.of("password", password)));
      page.session().flash("Password protection enabled.", "notice");
      page.session().signedIn(false);
      return page.session().attachTo(Requests.redirect("/"));
    }

    page.session().flash("Settings updated.");
    return page.session().attachTo(Requests.html(renderPage(page, new Store(componentClient),
        buildFilled(new Store(componentClient)))));
  }

  @Get("/settings/reset-api-key")
  public HttpResponse resetApiKey() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/settings/reset-api-key", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte value : bytes) {
      hex.append(String.format("%02x", value));
    }
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateApplication)
        .invoke(
            new SettingsEntity.UpdateApplication(Map.of("api_access_token", hex.toString())));
    page.session().flash("API Key was regenerated.");
    return page.session().attachTo(Requests.redirect("/settings#api"));
  }

  @Get("/settings/notification-logs")
  public HttpResponse notificationLogs() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/settings/notification-logs", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, "/settings/notification-logs");
    if (refusal != null) {
      return refusal;
    }
    List<String> log =
        componentClient
            .forKeyValueEntity(SettingsEntity.ID)
            .method(SettingsEntity::notificationLog)
            .invoke();
    List<String> lines =
        log.isEmpty()
            ? List.of("Notification logs are empty - no notifications sent yet.")
            : new ArrayList<>(log);
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("logs", lines);
    return page.session()
        .attachTo(Requests.html(Render.render(page, "notification-log.html", variables)));
  }

  @Get("/settings/toggle-all-paused")
  public HttpResponse toggleAllPaused() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/settings/toggle-all-paused", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    boolean now = !Fields.truthy(store.application().get("all_paused"));
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateApplication)
        .invoke(new SettingsEntity.UpdateApplication(Map.of("all_paused", now)));
    page.session()
        .flash(
            now
                ? "Automatic scheduling paused - checks will not be queued."
                : "Automatic scheduling resumed - checks will be queued normally.",
            "notice");
    return page.session().attachTo(Requests.redirect("/"));
  }

  @Get("/settings/toggle-all-muted")
  public HttpResponse toggleAllMuted() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/settings/toggle-all-muted", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    boolean now = !Fields.truthy(store.application().get("all_muted"));
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateApplication)
        .invoke(new SettingsEntity.UpdateApplication(Map.of("all_muted", now)));
    page.session().flash(now ? "All notifications muted." : "All notifications unmuted.", "notice");
    return page.session().attachTo(Requests.redirect("/"));
  }

  // ------------------------------------------------------------- notifications

  /** One backend today, so the index is where it sends you. */
  @Get("/settings/notifications/")
  public HttpResponse notificationsIndex() {
    return Requests.redirect("/settings/notifications/apprise");
  }

  @Get("/settings/notifications/apprise")
  public HttpResponse apprisePage() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/settings/notifications/apprise", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, "/settings/notifications/apprise");
    if (refusal != null) {
      return refusal;
    }
    Form form = Forms.appriseNotifications(extraTokens(store));
    form.fill(notificationDefaults(store));
    return page.session().attachTo(Requests.html(renderApprise(page, store, form)));
  }

  @Post("/settings/notifications/apprise")
  public HttpResponse appriseSubmit(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/settings/notifications/apprise", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    Form form = Forms.appriseNotifications(extraTokens(store));
    form.populate(submitted.values());
    if (!form.validate()) {
      page.session().flash("An error occurred, please see below.", "error");
      return page.session().attachTo(Requests.html(renderApprise(page, store, form)));
    }
    Map<String, Object> data = form.data();
    Map<String, Object> changes = new LinkedHashMap<>();
    for (String field : NOTIFICATION_FIELDS) {
      changes.put(field, data.get(field));
    }
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateApplication)
        .invoke(new SettingsEntity.UpdateApplication(changes));
    page.session().flash("Settings updated.");
    return page.session().attachTo(Requests.redirect("/settings/notifications/apprise"));
  }

  // ---------------------------------------------------------------------- AI

  @Get("/settings/llm/models")
  public HttpResponse llmModels() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/settings/llm/models", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String provider = Requests.queryValue(requestContext(), "provider", "").strip();
    String apiKey = Requests.queryValue(requestContext(), "api_key", "").strip();
    String apiBase = Requests.queryValue(requestContext(), "api_base", "").strip();
    if (provider.isEmpty()) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
          Map.of("models", List.of(), "error", "No provider specified"));
    }
    String unsafe = ProviderProbe.apiBaseRefusal(apiBase);
    if (unsafe != null) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
          Map.of("models", List.of(), "error", unsafe));
    }
    Map<String, Object> stored = LlmSettings.of(store.application());
    String storedBase = String.valueOf(stored.getOrDefault("api_base", "")).strip();
    if (apiKey.isEmpty()) {
      if (apiBase.equals(storedBase)) {
        apiKey = String.valueOf(stored.getOrDefault("api_key", ""));
      } else if (!apiBase.isEmpty()) {
        // The stored key is never sent anywhere the operator did not already save, because a
        // request forged in a signed-in browser would otherwise post it to whoever asked.
        return Requests.json(
            akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
            Map.of("models", List.of(), "error", CREDENTIAL_REFUSAL));
      }
    }
    try {
      List<String> models = ProviderProbe.availableModels(provider, apiKey, apiBase);
      Map<String, Object> answer = new LinkedHashMap<>();
      answer.put("models", models);
      answer.put("error", null);
      return Requests.json(answer);
    } catch (RuntimeException e) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
          Map.of("models", List.of(), "error", String.valueOf(e.getMessage())));
    }
  }

  @Get("/settings/llm/test")
  public HttpResponse llmTest() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/settings/llm/test", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> stored = LlmSettings.of(store.application());
    String requestedKey = Requests.queryValue(requestContext(), "api_key", "").strip();
    String requestedBase = Requests.queryValue(requestContext(), "api_base", "").strip();
    String storedBase = String.valueOf(stored.getOrDefault("api_base", "")).strip();

    String model =
        firstNonEmpty(
            Requests.queryValue(requestContext(), "model", ""),
            String.valueOf(stored.getOrDefault("model", "")));
    String apiKey =
        firstNonEmpty(requestedKey, String.valueOf(stored.getOrDefault("api_key", "")));
    String apiBase = firstNonEmpty(requestedBase, storedBase);

    if (model.isEmpty()) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
          Map.of("ok", false, "error", "No model configured."));
    }
    String unsafe = ProviderProbe.apiBaseRefusal(apiBase);
    if (unsafe != null) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST, Map.of("ok", false, "error", unsafe));
    }
    if (!requestedBase.isEmpty() && !requestedBase.equals(storedBase) && requestedKey.isEmpty()) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
          Map.of("ok", false, "error", CREDENTIAL_REFUSAL));
    }
    try {
      ProviderProbe.TestReply reply = ProviderProbe.testConnection(stored, model, apiKey, apiBase);
      if (reply.text().isEmpty()) {
        return Requests.json(
            akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
            Map.of(
                "ok", false,
                "error", "Model responded but returned empty content — check server logs."));
      }
      Map<String, Object> answer = new LinkedHashMap<>();
      answer.put("ok", true);
      answer.put("text", reply.text());
      answer.put("tokens", reply.totalTokens());
      return Requests.json(answer);
    } catch (RuntimeException e) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
          Map.of("ok", false, "error", String.valueOf(e.getMessage())));
    }
  }

  @Post("/settings/llm/clear")
  public HttpResponse llmClear() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/settings/llm/clear", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> stored = new LinkedHashMap<>(LlmSettings.of(store.application()));
    LlmSettings.CONNECTION_FIELDS.forEach(stored::remove);
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateApplication)
        .invoke(new SettingsEntity.UpdateApplication(Map.of("llm", stored)));
    page.session().flash("AI / LLM configuration removed.", "notice");
    return page.session().attachTo(Requests.redirect("/settings#ai"));
  }

  @Post("/settings/llm/clear-summary-cache")
  public HttpResponse llmClearSummaryCache() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/settings/llm/clear-summary-cache", "settings");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    int removed = 0;
    for (String uuid : store.watchUuids()) {
      for (String kind : store.notedSideStores(uuid, "change-summary-")) {
        store.deleteSideStore(uuid, kind);
        removed++;
      }
    }
    page.session()
        .flash("AI summary cache cleared (" + removed + " file(s) removed).", "notice");
    return page.session().attachTo(Requests.redirect("/settings#ai"));
  }

  // ------------------------------------------------------------------ helpers

  private static final String CREDENTIAL_REFUSAL =
      "api_key is required when api_base differs from the saved configuration. Refusing to send"
          + " the stored API key to a different endpoint.";

  private Form buildForm(Store store) {
    Form form = Forms.globalSettings(extraTokens(store));
    Form application = ((Form.Nested) form.field("application")).inner();
    // The last choice is the one that says "use the system default", which on the page that
    // sets the system default would be a setting that points at itself.
    dropSystemDefault(application.field("notification_format"));

    List<String> proxies = ApiSupport.proxyKeys(store);
    Form requests = ((Form.Nested) form.field("requests")).inner();
    if (proxies.isEmpty()) {
      requests.fields().remove("proxy");
    } else {
      List<String[]> options = new ArrayList<>();
      Map<String, Map<String, String>> configured =
          Choices.proxies(store.settings().settings(), DatastoreView.proxiesFromFile());
      for (Map.Entry<String, Map<String, String>> entry : configured.entrySet()) {
        options.add(new String[] {entry.getKey(), entry.getValue().get("label")});
      }
      ((io.akka.changedetection.forms.Fields.SelectField) requests.field("proxy"))
          .setChoices(options);
    }
    return form;
  }

  private Form buildFilled(Store store) {
    Form form = buildForm(store);
    form.fill(defaults(store));
    return form;
  }

  private Map<String, Object> defaults(Store store) {
    Map<String, Object> settings = Fields.deepCopy(store.settings().settings());
    @SuppressWarnings("unchecked")
    Map<String, Object> tree = (Map<String, Object>) settings.get("settings");
    Map<String, Object> out = new LinkedHashMap<>(tree);

    Map<String, Object> llm = new LinkedHashMap<>(LlmSettings.of(store.application()));
    // A password field never renders its stored value, so the key is blanked and an empty
    // submission is read as "leave it alone" rather than "clear it".
    llm.put("api_key", "");
    out.put("llm", llm);

    List<String> proxies = ApiSupport.proxyKeys(store);
    if (!proxies.isEmpty()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> requests = (Map<String, Object>) out.get("requests");
      Object configured = requests.get("proxy");
      String chosen =
          configured != null && proxies.contains(String.valueOf(configured))
              ? String.valueOf(configured)
              : proxies.get(0);
      requests.put("proxy", chosen);
      out.put("proxy_list", proxies.get(0));
    }
    return out;
  }

  private Map<String, Object> notificationDefaults(Store store) {
    Map<String, Object> application = store.application();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "notification_urls",
        application.get("notification_urls") == null
            ? new ArrayList<>()
            : application.get("notification_urls"));
    for (String field :
        List.of("notification_title", "notification_body", "notification_format", "base_url")) {
      Object value = application.get(field);
      out.put(field, value == null ? "" : value);
    }
    return out;
  }

  /**
   * The stored AI settings with the submitted ones over the top.
   *
   * <p>Three kinds of field are left out of the merge: ones the operator did not fill in, the
   * key when it came back blank, and the counters the service keeps for itself. A field the
   * environment fixes is also left out, because the page shows it as unchangeable and accepting
   * it back would let a submission change it anyway.
   */
  private Map<String, Object> mergedLlm(Store store, Map<String, Object> submitted) {
    Map<String, Object> input = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : submitted.entrySet()) {
      if (entry.getValue() != null) {
        input.put(entry.getKey(), entry.getValue());
      }
    }
    if (String.valueOf(input.getOrDefault("api_key", "")).strip().isEmpty()) {
      input.remove("api_key");
    }
    if (notBlank(System.getenv("LLM_TOKEN_BUDGET_MONTH"))) {
      input.remove("token_budget_month");
    }
    if (notBlank(System.getenv("LLM_MAX_INPUT_CHARS"))) {
      input.remove("max_input_chars");
    }
    LlmSettings.PROTECTED_FIELDS.forEach(input::remove);

    Map<String, Object> merged = LlmSettings.merge(LlmSettings.of(store.application()), input);
    // Clearing the model is how the page says "forget the provider"; everything the operator
    // chose about how the model is used survives it.
    if (String.valueOf(merged.getOrDefault("model", "")).strip().isEmpty()) {
      LlmSettings.CONNECTION_FIELDS.forEach(merged::remove);
    }
    return merged;
  }

  private void clearAllChecksums(Store store) {
    for (String uuid : store.watchUuids()) {
      store.saveSideStore(uuid, "raw-checksum", "");
    }
  }

  private Map<String, Object> extraTokens(Store store) {
    Map<String, Object> tokens = new LinkedHashMap<>();
    for (Watch watch : store.allWatches().values()) {
      Object extras = watch.asMap().get("extra_notification_token_values");
      if (extras instanceof Map<?, ?> map) {
        map.forEach((key, value) -> tokens.put(String.valueOf(key), value));
      }
    }
    return tokens;
  }

  private String renderPage(Render.Page page, Store store, Form form) {
    Map<String, Object> application = store.application();
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("form", form);
    variables.put("api_key", application.get("api_access_token"));
    variables.put("settings_application", application);
    variables.put("hide_remove_pass", notBlank(System.getenv("SALTED_PASS")));
    variables.put("emailprefix", envOrFalse("NOTIFICATION_MAIL_BUTTON_PREFIX"));
    variables.put("available_timezones", Choices.timezones());
    variables.put("timezone_default_config", application.get("scheduler_timezone_default"));
    variables.put(
        "utc_time", ZonedDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).toString());
    variables.put("uptime_seconds",
        (System.currentTimeMillis() - Site.startedAt().toEpochMilli()) / 1000.0);
    variables.put("python_version", System.getProperty("java.version"));
    variables.put("min_system_recheck_seconds", minimumRecheckSeconds());
    variables.put("active_plugins", new ArrayList<>());
    variables.put("plugin_tabs", new ArrayList<>());
    variables.put("plugin_forms", new LinkedHashMap<>());
    variables.put("worker_info", Render.workerStatus(store));
    variables.put("extra_notification_token_placeholder_info", new ArrayList<>());
    variables.putAll(llmVariables(store));
    return Render.render(page, "settings.html", variables);
  }

  private String renderApprise(Render.Page page, Store store, Form form) {
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("form", form);
    variables.put("emailprefix", envOrFalse("NOTIFICATION_MAIL_BUTTON_PREFIX"));
    variables.put("extra_notification_token_placeholder_info", new ArrayList<>());
    variables.put("settings_application", store.application());
    return Render.render(page, "apprise.html", variables);
  }

  private Map<String, Object> llmVariables(Store store) {
    Map<String, Object> stored = LlmSettings.of(store.application());
    boolean fromEnvironment = Evaluator.configuredByEnvironment();
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("llm_config", Evaluator.config(store.llmSurroundings()));
    variables.put("llm_env_configured", fromEnvironment);
    variables.put("llm_stored", stored);
    variables.put("llm_token_budget_month", Evaluator.globalTokenBudget(store.llmSurroundings()));
    variables.put("llm_token_budget_month_env", Evaluator.globalTokenBudget(null));
    variables.put("llm_max_input_chars_env", maxInputCharsFromEnvironment());
    variables.put("llm_effective_max_input_chars", Evaluator.maxInputChars(store.llmSurroundings()));
    // Cost is only shown to an operator paying for their own key; where the deployment supplies
    // one, the number would be somebody else's.
    variables.put("llm_show_costs", !fromEnvironment);
    return variables;
  }

  private static Set<String> zoneIds() {
    return new TreeSet<>(List.of(TimeZone.getAvailableIDs()));
  }

  static int minimumRecheckSeconds() {
    String configured = System.getenv("MINIMUM_SECONDS_RECHECK_TIME");
    if (configured != null && !configured.isBlank()) {
      try {
        return Integer.parseInt(configured.strip());
      } catch (NumberFormatException e) {
        // Falls through to the built-in floor.
      }
    }
    return 3;
  }

  /** The last choice on this page would be "use the system default", set on the page that is it. */
  private static void dropSystemDefault(io.akka.changedetection.forms.Field field) {
    if (field instanceof io.akka.changedetection.forms.Fields.SelectField select) {
      List<String[]> options = new ArrayList<>(select.choices());
      if (!options.isEmpty()) {
        options.remove(options.size() - 1);
        select.setChoices(options);
      }
    }
  }

  /** The character cap the environment fixes, or zero when it fixes none. */
  static int maxInputCharsFromEnvironment() {
    String configured = System.getenv("LLM_MAX_INPUT_CHARS");
    if (configured == null || !configured.strip().matches("[0-9]+")) {
      return 0;
    }
    return Integer.parseInt(configured.strip());
  }

  private static Object envOrFalse(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? false : value;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String firstNonEmpty(String first, String second) {
    String candidate = first == null ? "" : first.strip();
    return candidate.isEmpty() ? (second == null ? "" : second.strip()) : candidate;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map
        ? (Map<String, Object>) map
        : new LinkedHashMap<String, Object>();
  }
}
