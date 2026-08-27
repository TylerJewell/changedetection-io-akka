package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.application.Notifier;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.TemplateEngine;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.conditions.RuleSet;
import io.akka.changedetection.forms.Forms;
import io.akka.changedetection.llm.Evaluator;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.notification.NotificationFailed;
import io.akka.changedetection.notification.NotificationHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pieces of the interface that answer a script rather than draw a page.
 *
 * <p>Each is a control on some other page: the preview beside the filter boxes, the tick beside
 * a single rule, the button that sends one notification now, the two buttons on the banner that
 * offers price tracking, and the panel that tries every configured proxy. They share nothing
 * but that shape, which is why they are together rather than spread across the pages that call
 * them.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class UiExtrasEndpoint extends AbstractHttpEndpoint {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** What the operator decided about a page that offers structured price information. */
  static final String PRICE_TRACK_ACCEPT = "accepted";

  static final String PRICE_TRACK_REJECT = "rejected";

  private final ComponentClient componentClient;

  public UiExtrasEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  // ----------------------------------------------------- the filter preview

  @Post("/edit/{uuid}/preview-rendered")
  public HttpResponse previewRendered(String uuid, HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/edit/" + uuid + "/preview-rendered", "ui.ui_edit");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    FilterPreview.Result result =
        FilterPreview.run(store, uuid, FilterPreview.formValues(submitted.values()));
    return Requests.json(result.asMap());
  }

  // ------------------------------------------------------------ one rule

  @Post("/conditions/{watchUuid}/verify-condition-single-rule")
  public HttpResponse verifyConditionSingleRule(String watchUuid, HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(
            requestContext(),
            store,
            "/conditions/" + watchUuid + "/verify-condition-single-rule",
            "conditions");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    WatchState state = store.watch(watchUuid);
    if (!state.exists()) {
      return Requests.json(
          StatusCodes.NOT_FOUND, Map.of("status", "error", "message", "Watch not found"));
    }
    try {
      Requests.Submission submitted = Requests.submission(requestContext(), body);
      // The rule is judged against exactly what the operator's unsaved filters would produce,
      // not against the stored snapshot -- a rule about a price is about the price the filters
      // pull out, and against the whole page it would say something else.
      FilterPreview.Result preview =
          FilterPreview.run(store, watchUuid, FilterPreview.formValues(submitted.values()));

      String ruleJson = Requests.queryValue(requestContext(), "rule", "");
      List<Map<String, Object>> rules = new ArrayList<>();
      if (!ruleJson.isEmpty()) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rule = MAPPER.readValue(ruleJson, Map.class);
        rules.add(rule);
      }

      Watch temporary = state.asWatch();
      Map<String, Object> overrides = new LinkedHashMap<>();
      overrides.put("conditions", rules);
      // One rule, so how several would be combined cannot matter, and saying so keeps the
      // answer from depending on a setting the operator is not looking at.
      overrides.put("conditions_match_logic", "ALL");
      temporary.update(overrides);

      String text = preview.afterFilter();
      boolean passes = RuleSet.evaluate(temporary, text);
      Map<String, Object> facts = RuleSet.gatherFacts(temporary, text, null);

      Map<String, Object> answer = new LinkedHashMap<>();
      answer.put("status", "success");
      answer.put("result", passes);
      answer.put("data", facts);
      answer.put("message", passes ? "Condition passes" : "Condition does not pass");
      return Requests.json(answer);
    } catch (Exception e) {
      return Requests.json(
          StatusCodes.INTERNAL_SERVER_ERROR,
          Map.of("status", "error", "message", "Error verifying condition: " + e.getMessage()));
    }
  }

  // ------------------------------------------------------- one notification

  @Post("/notification/send-test/{watchUuid}")
  public HttpResponse sendTestForWatch(String watchUuid, HttpEntity.Strict body) {
    return sendTest(watchUuid, body);
  }

  @Post("/notification/send-test")
  public HttpResponse sendTestBare(HttpEntity.Strict body) {
    return sendTest(null, body);
  }

  // The original also declares this route with a trailing slash. The runtime treats the
  // two spellings as one path, so declaring both is refused; one method answers both.

  private HttpResponse sendTest(String watchUuid, HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/notification/send-test", "ui.ui_notification");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    String mode = Requests.queryValue(requestContext(), "mode", "");
    boolean fromGlobal = "global-settings".equals(mode);
    boolean fromGroup = "group-settings".equals(mode);

    String resolved = watchUuid;
    if ((resolved == null || resolved.isEmpty()) && (fromGlobal || fromGroup)) {
      List<String> all = store.watchUuids();
      if (!all.isEmpty()) {
        // Any watch will do: the test is about the address and the template, and both need a
        // watch only to have something to put in the placeholders.
        resolved = all.get(Math.floorMod(System.nanoTime(), all.size()));
      }
    }
    if (resolved == null || resolved.isEmpty()) {
      return Requests.text(
          StatusCodes.BAD_REQUEST,
          "Error: You must have atleast one watch configured for 'test notification' to work");
    }
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      return Requests.text(
          StatusCodes.BAD_REQUEST,
          "Error: You must have atleast one watch configured for 'test notification' to work");
    }
    Watch watch = state.asWatch();

    List<String> addresses = new ArrayList<>();
    for (String line : submitted.first("notification_urls").strip().split("\r?\n")) {
      if (!line.strip().isEmpty()) {
        addresses.add(line.strip());
      }
    }
    if (addresses.isEmpty() && !submitted.first("tags").strip().isEmpty()) {
      for (String name : submitted.first("tags").split(",")) {
        for (Map<String, Object> tag : store.tags().values()) {
          if (String.valueOf(tag.getOrDefault("title", "")).strip().equalsIgnoreCase(name.strip())
              && tag.get("notification_urls") instanceof List<?> urls
              && !urls.isEmpty()) {
            addresses.clear();
            for (Object url : urls) {
              addresses.add(String.valueOf(url));
            }
          }
        }
      }
    }
    if (addresses.isEmpty() && !fromGlobal && !fromGroup) {
      Object configured = store.application().get("notification_urls");
      if (configured instanceof List<?> urls) {
        for (Object url : urls) {
          addresses.add(String.valueOf(url));
        }
      }
    }
    if (addresses.isEmpty()) {
      return Requests.text("Error: No Notification URLs set/found");
    }

    String problem = io.akka.changedetection.forms.Checks.notificationUrlProblem(addresses);
    if (problem != null) {
      return Requests.text("Error:  " + problem.replace("'", "").replace(
          " is not a valid AppRise URL.", " is not a valid AppRise URL."));
    }

    Map<String, Object> notification = new LinkedHashMap<>();
    notification.put("notification_urls", addresses);
    String windowUrl = submitted.first("window_url");
    notification.put(
        "watch_url", windowUrl.isEmpty() ? "https://changedetection.io" : windowUrl);
    notification.put(
        "notification_format",
        firstNonBlank(
            submitted.first("notification_format"),
            String.valueOf(store.application().getOrDefault("notification_format", ""))));
    notification.put(
        "notification_title",
        firstNonBlank(
            submitted.first("notification_title"),
            String.valueOf(store.application().getOrDefault("notification_title", "")),
            "Test title"));
    notification.put(
        "notification_body",
        firstNonBlank(
            submitted.first("notification_body"),
            String.valueOf(store.application().getOrDefault("notification_body", "")),
            "Test body"));

    Notifier notifier = new Notifier(store, TemplateEngine.notifications());
    try {
      List<NotificationHandler.Rendered> rendered =
          notifier.sendTest(watch, notification, EXAMPLE_PREVIOUS, EXAMPLE_CURRENT);
      if (rendered.isEmpty()) {
        return Requests.text("Error: No Notification URLs set/found");
      }
    } catch (NotificationFailed e) {
      return Requests.text(StatusCodes.BAD_REQUEST, String.valueOf(e.getMessage()));
    } catch (RuntimeException e) {
      return Requests.text(StatusCodes.BAD_REQUEST, String.valueOf(e.getMessage()));
    }
    return Requests.text("OK - Sent test notifications");
  }

  /**
   * The two snapshots a test uses when the watch has no history of its own.
   *
   * <p>A test with nothing to compare would render an empty difference, and an operator would
   * read that as a broken template rather than as an empty history.
   */
  static final String EXAMPLE_PREVIOUS =
      "Example text: example test\nExample text: change detection is cool\n"
          + "Example text: some more examples\n";

  static final String EXAMPLE_CURRENT =
      "Example text: example test\nExample text: change detection is fantastic\n"
          + "Example text: even more examples\nExample text: a lot more examples";

  // --------------------------------------------------------- price tracking

  @Get("/price_data_follower/{uuid}/accept")
  public HttpResponse acceptPriceData(String uuid) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(
            requestContext(),
            operations.store(),
            "/price_data_follower/" + uuid + "/accept",
            "price_data_follower");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> change = new LinkedHashMap<>();
    change.put("track_ldjson_price_data", PRICE_TRACK_ACCEPT);
    change.put("processor", "restock_diff");
    operations.update(uuid, change);
    // What was stored was text; what will be stored is a price. Nothing can compare the two,
    // so the history goes rather than producing one meaningless difference.
    operations.clearHistory(uuid);
    operations.queueCheck(uuid);
    return page.session().attachTo(Requests.redirect("/"));
  }

  @Get("/price_data_follower/{uuid}/reject")
  public HttpResponse rejectPriceData(String uuid) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(
            requestContext(),
            operations.store(),
            "/price_data_follower/" + uuid + "/reject",
            "price_data_follower");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    operations.update(uuid, Map.of("track_ldjson_price_data", PRICE_TRACK_REJECT));
    return page.session().attachTo(Requests.redirect("/"));
  }

  // ------------------------------------------------------------- add a watch

  @Get("/add-watch-ui/")
  public HttpResponse addWatchUi() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/add-watch-ui/", "add_watch_ui");
    HttpResponse refusal = Guard.requireSignIn(page, "/add-watch-ui/");
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("form", Forms.quickWatch(new LinkedHashMap<>(store.tags())));
    variables.put("llm_configured", Evaluator.config(store.llmSurroundings()) != null);
    variables.put(
        "llm_intent_watch_placeholder",
        "e.g. tell me when the price drops below 100, or when a new article is posted");
    return page.session()
        .attachTo(Requests.html(Render.render(page, "add-watch-ui.html", variables)));
  }

  @Get("/add-watch-ui/snapshot")
  public HttpResponse addWatchSnapshot() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/add-watch-ui/snapshot", "add_watch_ui");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String url = Requests.queryValue(requestContext(), "url", "").strip();
    boolean allowFile =
        io.akka.changedetection.model.Fields.truthy(System.getenv("ALLOW_FILE_URI"));
    boolean allowRestricted =
        io.akka.changedetection.model.Fields.truthy(
            System.getenv("ALLOW_IANA_RESTRICTED_ADDRESSES"));
    // This makes the server fetch an address the caller chose and hands the result straight
    // back, so it is refused here rather than relying on the check a real check would do.
    io.akka.changedetection.model.UrlSafety.Verdict verdict =
        io.akka.changedetection.model.UrlSafety.isFetchAllowed(url, allowFile, allowRestricted);
    if (!verdict.allowed()) {
      return Requests.text(StatusCodes.BAD_REQUEST, verdict.reason());
    }
    try {
      Snapshot snapshot = LivePreview.capture(store, url);
      Map<String, Object> answer = new LinkedHashMap<>();
      answer.put("temporary_uuid", snapshot.temporaryUuid());
      answer.put(
          "screenshot",
          "data:image/jpeg;base64,"
              + java.util.Base64.getEncoder().encodeToString(snapshot.screenshot()));
      answer.put("xpath_data", Requests.parseJson(snapshot.xpathData()));
      return Requests.json(answer);
    } catch (RuntimeException e) {
      String message = String.valueOf(e.getMessage());
      if (message.contains("ECONNREFUSED") || message.contains("Connection refused")) {
        return Requests.text(
            StatusCodes.BAD_GATEWAY,
            "Unable to start the Playwright Browser session, is sockpuppetbrowser running? The"
                + " live preview needs a fetcher that supports Javascript and screenshots.");
      }
      return Requests.text(
          StatusCodes.BAD_GATEWAY,
          message.isEmpty() ? "Could not fetch the page" : message.split("\r?\n")[0]);
    }
  }

  /** What one live look at a page produced. */
  record Snapshot(String temporaryUuid, byte[] screenshot, String xpathData, String html) {}

  // ------------------------------------------------------------ the proxies

  @Get("/check_proxy/{uuid}/status")
  public HttpResponse proxyStatus(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/check_proxy/" + uuid + "/status", "check_proxies");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    return Requests.json(ProxyChecks.status(uuid));
  }

  @Get("/check_proxy/{uuid}/start")
  public HttpResponse proxyStart(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/check_proxy/" + uuid + "/start", "check_proxies");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    List<String> proxies = ApiSupport.proxyKeys(store);
    if (proxies.isEmpty()) {
      return Requests.json(new LinkedHashMap<String, Object>());
    }
    return Requests.json(ProxyChecks.start(store, uuid, proxies));
  }

  private static String firstNonBlank(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.strip().isEmpty()) {
        return candidate.strip();
      }
    }
    return "";
  }
}
