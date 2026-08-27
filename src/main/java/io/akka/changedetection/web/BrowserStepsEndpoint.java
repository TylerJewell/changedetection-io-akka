package io.akka.changedetection.web;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.fetchers.BrowserFetcher;
import io.akka.changedetection.fetchers.BrowserScripts;
import io.akka.changedetection.fetchers.BrowserSteps;
import io.akka.changedetection.fetchers.CdpClient;
import io.akka.changedetection.fetchers.Fetcher;
import io.akka.changedetection.fetchers.Fetchers;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live browser the operator builds a sequence of steps in.
 *
 * <p>One real browser is held open per session while the operator works, because the point of
 * the feature is that step three runs against whatever step two left on the screen -- a session
 * that reconnected between steps would show a signed-out page every time.
 *
 * <p>A session is therefore a resource with a life of its own, and both ends of that life are
 * handled here: opening one closes any the same watch already had, and every one is given up
 * after a fixed idle period whether or not the operator came back.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class BrowserStepsEndpoint extends AbstractHttpEndpoint {

  /** How long an unused browser is kept before it is given up. */
  private static final long SESSION_LIFETIME_MILLIS = 10 * 60 * 1000L;

  private static final Map<String, Live> SESSIONS = new ConcurrentHashMap<>();

  private static final Map<String, String> BY_WATCH = new ConcurrentHashMap<>();

  private final ComponentClient componentClient;

  public BrowserStepsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** One browser, attached to one page, with the moment it was opened. */
  static final class Live implements AutoCloseable {
    final CdpClient client;
    final String sessionId;
    final long openedAt = System.currentTimeMillis();
    volatile String currentUrl = "";

    Live(CdpClient client, String sessionId) {
      this.client = client;
      this.sessionId = sessionId;
    }

    @Override
    public void close() {
      try {
        client.close();
      } catch (RuntimeException e) {
        // A browser that has already gone is the state we wanted.
      }
    }
  }

  @Get("/browser-steps/browsersteps_start_session")
  public HttpResponse startSession() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(
            requestContext(), store, "/browser-steps/browsersteps_start_session", "browser_steps");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    expireOldSessions();

    String watchUuid = Requests.queryValue(requestContext(), "uuid", "");
    if (watchUuid.isEmpty()) {
      return Requests.text(StatusCodes.INTERNAL_SERVER_ERROR, "No Watch UUID specified");
    }
    closeSessionFor(watchUuid);

    String sessionKey = UUID.randomUUID().toString();
    try {
      Live live = open(store, watchUuid);
      SESSIONS.put(sessionKey, live);
      BY_WATCH.put(watchUuid, sessionKey);
    } catch (RuntimeException e) {
      String message = String.valueOf(e.getMessage());
      if (message.contains("ECONNREFUSED") || message.contains("Connection refused")) {
        return Requests.text(
            StatusCodes.UNAUTHORIZED,
            "Unable to start the Playwright Browser session, is sockpuppetbrowser running?"
                + " Network configuration is OK?");
      }
      return Requests.text(StatusCodes.UNAUTHORIZED, message);
    }
    return Requests.json(Map.of("browsersteps_session_id", sessionKey));
  }

  @Get("/browser-steps/browsersteps_image")
  public HttpResponse stepImage() {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/browser-steps/browsersteps_image", "browser_steps");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String uuid = Requests.queryValue(requestContext(), "uuid", "");
    String step = Requests.queryValue(requestContext(), "step_n", "");
    String kind = Requests.queryValue(requestContext(), "type", "");
    int stepNumber;
    try {
      stepNumber = Integer.parseInt(step.strip());
    } catch (RuntimeException e) {
      stepNumber = 0;
    }
    String name =
        "before".equals(kind) ? "step_before-" + stepNumber : "step_" + stepNumber;
    String stored = stepNumber == 0 ? null : store.sideStore(uuid, name);
    if (stored == null || stored.isEmpty()) {
      return Requests.text(
          StatusCodes.UNAUTHORIZED,
          "Unable to fetch image, is the URL correct? does the watch exist? does the"
              + " step_type-n.jpeg exist?");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(stored);
    } catch (IllegalArgumentException e) {
      bytes = stored.getBytes(StandardCharsets.UTF_8);
    }
    return Requests.bytes(
            StatusCodes.OK,
            ContentTypes.create(akka.http.javadsl.model.MediaTypes.IMAGE_JPEG),
            bytes)
        .addHeader(RawHeader.create("Cache-Control", "no-cache, no-store, must-revalidate"))
        .addHeader(RawHeader.create("Pragma", "no-cache"))
        .addHeader(RawHeader.create("Expires", "0"));
  }

  @Post("/browser-steps/browsersteps_update")
  public HttpResponse update(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/browser-steps/browsersteps_update", "browser_steps");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String sessionKey = Requests.queryValue(requestContext(), "browsersteps_session_id", "");
    if (sessionKey.isEmpty()) {
      return Requests.text(
          StatusCodes.INTERNAL_SERVER_ERROR, "No browsersteps_session_id specified");
    }
    Live live = SESSIONS.get(sessionKey);
    if (live == null) {
      return Requests.text(StatusCodes.INTERNAL_SERVER_ERROR, "No session exists under that ID");
    }

    String watchUuid = Requests.queryValue(requestContext(), "uuid", "");
    boolean gotoFirst =
        !Requests.queryValue(requestContext(), "goto_website_url_first_step", "").isEmpty();
    Requests.Submission submitted = Requests.submission(requestContext(), body);

    String operation;
    String selector;
    String optionalValue;
    boolean isLastStep = false;
    if (gotoFirst) {
      operation = "Goto site";
      selector = null;
      optionalValue = null;
    } else {
      operation = submitted.first("operation");
      selector = submitted.first("selector");
      optionalValue = submitted.first("optional_value");
      isLastStep = Fields.truthy(submitted.first("is_last_step"));
    }

    Map<String, Object> step = new LinkedHashMap<>();
    step.put("operation", operation);
    step.put("selector", selector);
    step.put("optional_value", optionalValue);
    try {
      BrowserSteps.run(live.client, live.sessionId, step, live.currentUrl, 0);
    } catch (RuntimeException e) {
      String message = String.valueOf(e.getMessage());
      return Requests.text(StatusCodes.UNAUTHORIZED, message.split("\r?\n")[0]);
    }

    byte[] screenshot;
    String xpathData;
    try {
      screenshot = BrowserFetcher.screenshot(live.client, live.sessionId, "jpeg");
      BrowserSteps.evaluate(live.client, live.sessionId, "var include_filters='';");
      JsonNode evaluated =
          BrowserSteps.evaluate(
              live.client, live.sessionId, BrowserScripts.xpathElementScraper());
      xpathData = BrowserFetcher.valueAsJson(evaluated);
      if (isLastStep && !watchUuid.isEmpty() && store.watch(watchUuid).exists()) {
        store.saveSideStore(
            watchUuid, "screenshot", Base64.getEncoder().encodeToString(screenshot));
        store.saveSideStore(watchUuid, "elements", xpathData);
      }
    } catch (RuntimeException e) {
      return Requests.text(
          StatusCodes.UNAUTHORIZED, "Error fetching screenshot and element data - " + e);
    }

    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put(
        "screenshot", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(screenshot));
    answer.put("xpath_data", Requests.parseJson(xpathData));
    answer.put("session_age_start", live.openedAt / 1000.0);
    answer.put(
        "browser_time_remaining",
        Math.max(
            0,
            Math.round(
                (SESSION_LIFETIME_MILLIS - (System.currentTimeMillis() - live.openedAt))
                    / 1000.0)));
    return Requests.json(answer);
  }

  // ------------------------------------------------------------------ pieces

  private Live open(Store store, String watchUuid) {
    WatchState state = store.watch(watchUuid);
    String url = state.exists() ? state.asWatch().fields().string("url", "") : "";
    boolean allowFile = Fields.truthy(System.getenv("ALLOW_FILE_URI"));
    boolean allowRestricted = Fields.truthy(System.getenv("ALLOW_IANA_RESTRICTED_ADDRESSES"));
    if (!url.isEmpty()) {
      UrlSafety.Verdict verdict = UrlSafety.isFetchAllowed(url, allowFile, allowRestricted);
      if (!verdict.allowed()) {
        throw new IllegalStateException(verdict.reason());
      }
    }

    String connection = browserConnectionUrl(store);
    CdpClient client = CdpClient.connect(connection, 30_000);
    JsonNode target = client.send("Target.createTarget", Map.of("url", "about:blank"));
    String targetId = target.path("targetId").asText();
    JsonNode attached =
        client.send("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true));
    String sessionId = attached.path("sessionId").asText();
    client.send("Network.enable", Map.of(), sessionId);
    client.send("Page.enable", Map.of(), sessionId);
    client.send("Runtime.enable", Map.of(), sessionId);
    Live live = new Live(client, sessionId);
    live.currentUrl = url;
    return live;
  }

  static String browserConnectionUrl(Store store) {
    Fetcher fetcher =
        Fetchers.resolve(
            "",
            String.valueOf(store.application().getOrDefault("fetch_backend", "html_requests")),
            false);
    if (fetcher instanceof BrowserFetcher browser) {
      return browser.connectionUrl();
    }
    return BrowserFetcher.playwright().connectionUrl();
  }

  static void closeSessionFor(String watchUuid) {
    String existing = BY_WATCH.remove(watchUuid);
    if (existing != null) {
      Live live = SESSIONS.remove(existing);
      if (live != null) {
        live.close();
      }
    }
  }

  /**
   * Gives up any browser nobody has touched for the session lifetime.
   *
   * <p>A browser is a process somewhere, and a page the operator abandoned holds one open with
   * no route back to it -- there is no close request, because the operator closed a tab.
   */
  static void expireOldSessions() {
    long now = System.currentTimeMillis();
    for (Map.Entry<String, Live> entry : SESSIONS.entrySet()) {
      if (now - entry.getValue().openedAt > SESSION_LIFETIME_MILLIS) {
        SESSIONS.remove(entry.getKey());
        entry.getValue().close();
        BY_WATCH.values().remove(entry.getKey());
      }
    }
  }
}
