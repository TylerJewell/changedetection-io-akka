package io.akka.changedetection.application;

import io.akka.changedetection.conditions.RuleSet;
import io.akka.changedetection.fetchers.Fetcher;
import io.akka.changedetection.fetchers.Fetchers;
import io.akka.changedetection.llm.Evaluator;
import io.akka.changedetection.model.LlmSettings;
import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.CheckOutcome;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.ProcessorExceptions;
import io.akka.changedetection.processors.RestockProcessor;
import io.akka.changedetection.processors.TextJsonDiffProcessor;
import io.akka.changedetection.text.HtmlTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One whole check: fetch the page, decide, store what changed, and say so.
 *
 * <p>Every way a check can end other than "changed" or "unchanged" is a distinct outcome the
 * operator sees, so the failures are enumerated here rather than collapsed into one. Two of
 * them count towards a threshold that eventually sends a warning, and the rest do not; getting
 * that wrong means either a silent watch or a stream of warnings about a page that is fine.
 */
public final class CheckRunner {

  private final Store store;
  private final Notifier notifier;

  public CheckRunner(Store store, Notifier notifier) {
    this.store = store;
    this.notifier = notifier;
  }

  /** What happened, for the caller that asked for the check. */
  public record Result(String verdict, String error, boolean notified) {}

  public Result run(String uuid) {
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return new Result("gone", null, false);
    }
    Watch watch = state.asWatch();
    Map<String, Object> application = store.application();
    SettingsState settings = store.settings();
    long startedAt = System.currentTimeMillis() / 1000;

    store.client().forEventSourcedEntity(uuid).method(WatchEntity::startCheck).invoke();

    Map<String, Object> finalUpdates = new LinkedHashMap<>();
    String verdict;
    String error = null;
    boolean notified = false;

    try {
      Fetched fetched = fetch(watch, settings);
      finalUpdates.put(
          "content-type", fetched.contentType() == null ? "" : fetched.contentType());

      CheckOutcome outcome = decide(watch, fetched, application);
      finalUpdates.putAll(outcome.updates());
      finalUpdates.put("last_error", false);
      if (!Fields.truthy(watch.fields().get("ignore_status_codes"))) {
        finalUpdates.put("consecutive_filter_failures", 0);
      }

      String server = fetched.header("server");
      if (server != null && !server.isBlank()) {
        finalUpdates.put(
            "remote_server_reply",
            server.strip().toLowerCase(Locale.ROOT)
                .substring(0, Math.min(255, server.strip().length())));
      }
      String contentType = fetched.contentType();
      if (contentType != null && contentType.contains("html")) {
        String pageTitle = HtmlTools.extractTitle(fetched.content);
        if (pageTitle != null && !pageTitle.isBlank()) {
          finalUpdates.put("page_title", pageTitle.strip());
        }
      }

      boolean changed = outcome.changed();
      AiVerdict verdict_ai = evaluateWithAi(uuid, watch, outcome, changed);
      changed = verdict_ai.changed();
      finalUpdates.putAll(verdict_ai.updates());
      long timestamp = startedAt;
      if (changed || watch.historyCount() == 0) {
        // A version and the moment it was taken are one record, and two versions may not share
        // a moment: the difference view addresses a version by its moment, so a collision would
        // make one of them unreachable.
        if (watch.historyCount() > 0
            && timestamp == watch.history().get(watch.history().size() - 1)) {
          timestamp = timestamp + 1;
        }
        store.saveSnapshot(uuid, timestamp, outcome.contents());
        store.client()
            .forEventSourcedEntity(uuid)
            .method(WatchEntity::recordSnapshot)
            .invoke(
                new WatchEntity.RecordSnapshot(
                    timestamp,
                    String.valueOf(outcome.updates().getOrDefault("previous_md5", "none")),
                    outcome.contents().length()));
        trimHistory(uuid, watch, application);
        cacheAiSummary(uuid, watch, verdict_ai, timestamp);
      }

      verdict = changed ? "changed" : "unchanged";

      int historyAfter = watch.historyCount() + (changed || watch.historyCount() == 0 ? 1 : 0);
      if (changed && historyAfter >= 2 && !watch.fields().bool("notification_muted")) {
        notified = notifier.contentChanged(uuid, timestamp, verdict_ai.updates());
        if (notified) {
          finalUpdates.put(
              "notification_alert_count", watch.fields().integer("notification_alert_count", 0) + 1);
        }
      }
    } catch (ProcessorExceptions.ChecksumWasTheSame e) {
      verdict = "skipped";
      finalUpdates.put("last_error", false);
    } catch (ProcessorExceptions.FilterNotFound e) {
      verdict = "filter-not-found";
      error =
          "Warning, no filters were found, no change detection ran - Did the page change "
              + "layout? update your Visual Filter if necessary.";
      finalUpdates.put("last_error", error);
      notified = countFilterFailure(uuid, watch, application);
    } catch (ProcessorExceptions.BrowserStepFailed e) {
      verdict = "step-failed";
      error =
          "Browser step at position " + e.step() + " could not run, check the watch, add a delay "
              + "if necessary, view Browser Steps to see screenshot at that step.";
      finalUpdates.put("last_error", error);
      finalUpdates.put("browser_steps_last_error_step", e.step());
      notified = countFilterFailure(uuid, watch, application);
    } catch (ProcessorExceptions.ReplyWithContentButNoText e) {
      verdict = "no-text";
      String extra = "";
      if (e.hasFilters()) {
        String images = HtmlTools.includeFilters("img", e.htmlContent(), false);
        extra =
            images.isEmpty()
                ? ", it's possible that the filters were found, but contained no usable text."
                : ", it's possible that the filters you have give an empty result or contain "
                    + "only an image.";
      }
      error =
          "Got HTML content but no text found (With " + e.statusCode() + " reply code)" + extra;
      finalUpdates.put("last_error", error);
    } catch (ProcessorExceptions.NonSuccessStatus e) {
      verdict = "error";
      error = statusMessage(e.statusCode());
      finalUpdates.put("last_error", error);
    } catch (ProcessorExceptions.EmptyReply e) {
      verdict = "error";
      error =
          "EmptyReply - try increasing 'Wait seconds before extracting text', Status Code "
              + e.statusCode();
      finalUpdates.put("last_error", error);
      finalUpdates.put("last_check_status", e.statusCode());
    } catch (ProcessorExceptions.ScreenshotUnavailable e) {
      verdict = "error";
      error =
          "Screenshot unavailable, page did not render fully in the expected time or page was "
              + "too long - try increasing 'Wait seconds before extracting text'";
      finalUpdates.put("last_error", error);
      finalUpdates.put("last_check_status", e.statusCode());
    } catch (ProcessorExceptions.JsActionException e) {
      verdict = "error";
      error = "Error running JS Actions - Page request - " + e.getMessage();
      finalUpdates.put("last_error", error);
      finalUpdates.put("last_check_status", e.statusCode());
    } catch (ProcessorExceptions.BrowserStepsInUnsupportedFetcher e) {
      verdict = "error";
      error =
          "This watch has Browser Steps configured and so it cannot run with the 'Basic fast "
              + "Plaintext/HTTP Client', either remove the Browser Steps or select a Chrome "
              + "fetcher.";
      finalUpdates.put("last_error", error);
    } catch (ProcessorExceptions.BrowserConnectError e) {
      verdict = "error";
      error = e.getMessage();
      finalUpdates.put("last_error", error);
    } catch (ProcessorExceptions.BrowserFetchTimedOut e) {
      verdict = "error";
      error = e.getMessage();
      finalUpdates.put("last_error", error);
    } catch (ProcessorExceptions.PageUnloadable e) {
      verdict = "error";
      error =
          e.getMessage() == null || e.getMessage().isBlank()
              ? "Page request from server didnt respond correctly"
              : "Page request from server didnt respond correctly - " + e.getMessage();
      finalUpdates.put("last_error", error);
      finalUpdates.put("last_check_status", e.statusCode());
      finalUpdates.put("has_ldjson_price_data", null);
    } catch (ProcessorExceptions.ProcessorException e) {
      verdict = "error";
      error = e.getMessage();
      finalUpdates.put("last_error", error);
    } catch (RuntimeException e) {
      verdict = "error";
      error = "Exception: " + e.getMessage();
      finalUpdates.put("last_error", error);
    }

    finalUpdates.put("check_count", watch.fields().integer("check_count", 0) + 1);
    finalUpdates.put(
        "fetch_time",
        Math.round((System.currentTimeMillis() / 1000.0 - startedAt) * 1000.0) / 1000.0);

    store.client()
        .forEventSourcedEntity(uuid)
        .method(WatchEntity::recordCheck)
        .invoke(new WatchEntity.RecordCheck(finalUpdates, startedAt));
    store.client().forEventSourcedEntity(uuid).method(WatchEntity::finishCheck).invoke();
    // Every open page is told the row moved, which is the whole reason the check exists as
    // far as anybody watching the list is concerned.
    io.akka.changedetection.web.StreamHub.publish(
        "watch_update", java.util.Map.of("watch", java.util.Map.of("uuid", uuid)));

    return new Result(verdict, error, notified);
  }

  /**
   * Keeps the summary where the difference page will look for it.
   *
   * <p>Written only after the new version is stored, because the name it is filed under names
   * the pair of versions it describes, and the newer of the two does not exist until then.
   */
  private void cacheAiSummary(String uuid, Watch watch, AiVerdict verdict, long toVersion) {
    if (verdict.summary().isEmpty() || verdict.fromVersion() == null) {
      return;
    }
    Evaluator.Surroundings around = store.llmSurroundings();
    Map<String, Object> llm = LlmSettings.of(store.application());
    String cachePrompt =
        Evaluator.summaryCachePrompt(
            Evaluator.effectiveSummaryPrompt(watch, around),
            (int) longOf(llm.get("max_summary_tokens")),
            Evaluator.DiffPrefs.standard(),
            String.valueOf(llm.getOrDefault("model", "")));
    store.saveSideStore(
        uuid,
        "change-summary-" + verdict.fromVersion() + "-to-" + toVersion + "-"
            + Evaluator.hexDigest("MD5", cachePrompt).substring(0, 8),
        verdict.summary());
  }

  private static long longOf(Object value) {
    return value instanceof Number number
        ? number.longValue()
        : LlmSettings.DEFAULT_MAX_SUMMARY_TOKENS;
  }

  /** What the AI evaluation concluded, and what it wants recorded on the watch. */
  record AiVerdict(boolean changed, Map<String, Object> updates, String summary,
      Long fromVersion) {}

  /**
   * Asks the model whether this change is one the operator cares about, and describes it.
   *
   * <p>Only when there is something to compare against. On a watch's first check every line is
   * new, so a model asked what changed would describe the whole page as a change -- "the price
   * is now 860" on first sight of a price that has always been 860.
   *
   * <p>Never fatal. A failure here leaves the change exactly as the rest of the check found it.
   */
  private AiVerdict evaluateWithAi(
      String uuid, Watch watch, CheckOutcome outcome, boolean changed) {
    Map<String, Object> updates = new LinkedHashMap<>();
    Evaluator.Surroundings around = store.llmSurroundings();
    Map<String, Object> llm = LlmSettings.of(store.application());
    boolean masterEnabled =
        Fields.truthy(llm.get("enabled")) && !Evaluator.featuresDisabledByEnvironment();

    if (masterEnabled
        && "skip_check".equals(String.valueOf(llm.getOrDefault("budget_action", "")))
        && Evaluator.globalBudgetExceeded(around)) {
      return new AiVerdict(false, updates, "", null);
    }
    if (!changed || watch.historyCount() < 1) {
      return new AiVerdict(changed, updates, "", null);
    }

    try {
      Map<String, Object> config = Evaluator.config(around);
      if (config == null || !masterEnabled) {
        return new AiVerdict(changed, updates, "", null);
      }
      List<Long> history = watch.history();
      Long fromVersion = history.isEmpty() ? null : history.get(history.size() - 1);
      String difference = outcome.contents();
      if (fromVersion != null) {
        String previous = store.snapshot(uuid, fromVersion);
        List<String> unified =
            io.akka.changedetection.text.SequenceMatcher.unifiedDiff(
                io.akka.changedetection.text.PythonText.splitLinesKeepEnds(
                    previous == null ? "" : previous),
                io.akka.changedetection.text.PythonText.splitLinesKeepEnds(outcome.contents()),
                "", "", "", "", 3, "");
        difference = unified.isEmpty() ? outcome.contents() : String.join("", unified);
      }

      String intent = Evaluator.resolveIntent(watch, around)[0];
      if (!intent.isEmpty()) {
        Map<String, Object> result =
            Evaluator.evaluateChange(watch, around, difference, outcome.contents());
        updates.put("_llm_result", result);
        updates.put("_llm_intent", intent);
        if (result != null && !Fields.truthy(result.getOrDefault("important", true))) {
          changed = false;
        }
      }

      String summary = "";
      if (changed && notifier.willNotify(watch)) {
        summary = Evaluator.summariseChange(watch, around, difference, outcome.contents());
        if (!summary.isEmpty()) {
          updates.put("_llm_change_summary", summary);
        }
      }
      return new AiVerdict(changed, updates, summary, fromVersion);
    } catch (RuntimeException e) {
      return new AiVerdict(changed, updates, "", null);
    }
  }

  private void trimHistory(String uuid, Watch watch, Map<String, Object> application) {
    Integer limit = watch.fields().integer("history_snapshot_max_length");
    if (limit == null) {
      Object global = application.get("history_snapshot_max_length");
      limit = global == null ? null : new Fields(Map.of("v", global)).integer("v");
    }
    if (limit == null || limit <= 0) {
      return;
    }
    List<Long> dropped =
        store.client()
            .forEventSourcedEntity(uuid)
            .method(WatchEntity::trimHistory)
            .invoke(new WatchEntity.TrimHistory(limit));
    for (long timestamp : dropped) {
      store.deleteSnapshot(uuid, timestamp);
    }
  }

  /**
   * A filter that did not match, counted towards the threshold that sends a warning.
   *
   * <p>Only counted where the watch asked to be warned, and reset once the warning goes, so a
   * page that stays broken produces one warning per threshold rather than one per check.
   */
  private boolean countFilterFailure(String uuid, Watch watch, Map<String, Object> application) {
    if (!watch.fields().bool("filter_failure_notification_send")) {
      return false;
    }
    int count = watch.fields().integer("consecutive_filter_failures", 0) + 1;
    int threshold =
        new Fields(application)
            .integer(
                "filter_failure_notification_threshold_attempts",
                AppSettings.FILTER_FAILURE_THRESHOLD_DEFAULT);
    boolean sent = false;
    if (count >= threshold) {
      if (!watch.fields().bool("notification_muted")) {
        sent = notifier.filterFailure(uuid);
      }
      count = 0;
    }
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("consecutive_filter_failures", count);
    store.client()
        .forEventSourcedEntity(uuid)
        .method(WatchEntity::recordCheck)
        .invoke(new WatchEntity.RecordCheck(update, System.currentTimeMillis() / 1000));
    return sent;
  }

  static String statusMessage(int status) {
    return switch (status) {
      case 403 -> "Error - 403 (Access denied) received";
      case 404 -> "Error - 404 (Page not found) received";
      case 407 ->
          "Error - 407 (Proxy authentication required) received, did you need a username and "
              + "password for the proxy?";
      case 500 -> "Error - 500 (Internal server error) received from the web site";
      default -> {
        String extra = String.valueOf(status).startsWith("4") ? " (Access denied or blocked)" : "";
        yield "Error - Request returned a HTTP error code " + status + extra;
      }
    };
  }

  private Fetched fetch(Watch watch, SettingsState settings) {
    Map<String, Object> application = settings.application();
    Map<String, Object> requests = settings.requests();

    Fetcher.Request request = new Fetcher.Request();
    request.url = watch.link(text -> text);
    request.watchUuid = watch.uuid();
    request.timeoutSeconds = new Fields(requests).integer("timeout", 45);
    request.method = watch.fields().string("method", "GET");
    request.body = watch.fields().string("body");
    request.emptyPagesAreAChange = Fields.truthy(application.get("empty_pages_are_a_change"));
    request.isBinary = watch.isPdf();
    request.includeFilters = watch.fields().strings("include_filters");
    request.browserSteps = watch.fields().maps("browser_steps");
    request.javascriptToRun = watch.fields().string("webdriver_js_execute_code");
    request.waitSeconds =
        watch.fields().integer("webdriver_delay") != null
            ? watch.fields().integer("webdriver_delay")
            : new Fields(application).integer("webdriver_delay");

    Object ignoreStatus = watch.fields().get("ignore_status_codes");
    if (ignoreStatus instanceof List<?> list) {
      List<Integer> accepted = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Number n) {
          accepted.add(n.intValue());
        } else {
          try {
            accepted.add(Integer.parseInt(String.valueOf(item).strip()));
          } catch (NumberFormatException e) {
            // A status code that is not a number is not a status code.
          }
        }
      }
      request.acceptedStatusCodes = accepted;
    } else {
      request.ignoreStatusCodes =
          Fields.truthy(ignoreStatus) || Fields.truthy(application.get("ignore_status_codes"));
    }

    Map<String, String> headers = new LinkedHashMap<>(settings.headers());
    Object userAgents = requests.get("default_ua");
    if (userAgents instanceof Map<?, ?> map) {
      Object agent = map.get(watch.fetchBackend());
      if (agent != null && !String.valueOf(agent).isBlank()) {
        headers.put("User-Agent", String.valueOf(agent));
      }
    }
    for (Map.Entry<String, Object> entry : watch.fields().map("headers").entrySet()) {
      headers.put(entry.getKey(), String.valueOf(entry.getValue()));
    }
    request.headers = headers;

    String proxy = watch.fields().string("proxy");
    if (proxy == null || proxy.isBlank()) {
      Object global = requests.get("proxy");
      proxy = global == null ? null : String.valueOf(global);
    }
    request.proxy = proxy;

    Fetcher fetcher =
        Fetchers.resolve(
            watch.fetchBackend(),
            String.valueOf(application.getOrDefault("fetch_backend", "html_requests")),
            watch.isPdf());
    return fetcher.fetch(request);
  }

  private CheckOutcome decide(Watch watch, Fetched fetched, Map<String, Object> application) {
    String processor = watch.fields().string("processor", "text_json_diff");
    ProcessorEnvironment environment = new ProcessorEnvironment(store);
    return switch (processor) {
      case "restock_diff" -> new RestockProcessor(environment).run(watch, fetched);
      case "image_ssim_diff" ->
          new io.akka.changedetection.processors.ImageSsimProcessor(environment)
              .run(watch, fetched);
      default -> new TextJsonDiffProcessor(environment).run(watch, fetched);
    };
  }

  /** What the processors read from the store. */
  public static final class ProcessorEnvironment
      implements TextJsonDiffProcessor.Environment,
          RestockProcessor.Environment,
          io.akka.changedetection.processors.ImageSsimProcessor.Environment {

    private final Store store;

    public ProcessorEnvironment(Store store) {
      this.store = store;
    }

    @Override
    public List<String> tagOverrides(String watchUuid, String attribute) {
      return store.tagOverrides(watchUuid, attribute);
    }

    @Override
    public Map<String, Object> application() {
      return store.application();
    }

    @Override
    public String lastRawContentChecksum(String watchUuid) {
      String value = store.sideStore(watchUuid, "raw-checksum");
      return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public void updateLastRawContentChecksum(String watchUuid, String checksum) {
      store.saveSideStore(watchUuid, "raw-checksum", checksum);
    }

    @Override
    public String snapshot(String watchUuid, long timestamp) {
      return store.snapshot(watchUuid, timestamp);
    }

    @Override
    public String lastFetchedTextBeforeFilters(String watchUuid) {
      String value = store.sideStore(watchUuid, "last-fetched");
      return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public void saveLastFetchedTextBeforeFilters(String watchUuid, String text) {
      store.saveSideStore(watchUuid, "last-fetched", text);
    }

    @Override
    public boolean conditionsAllow(Watch watch, String text) {
      return RuleSet.evaluate(
          watch,
          text,
          subject -> {
            List<Long> history = subject.history();
            return history.isEmpty()
                ? null
                : store.snapshot(subject.uuid(), history.get(history.size() - 1));
          });
    }

    @Override
    public String pdfToHtml(byte[] rawContent) {
      return io.akka.changedetection.processors.PdfToHtml.convert(rawContent);
    }

    @Override
    public Map<String, Object> processorConfig(String watchUuid, String name) {
      String stored = store.sideStore(watchUuid, "config-" + name);
      if (stored == null || stored.isEmpty()) {
        return new LinkedHashMap<>();
      }
      try {
        return io.akka.changedetection.text.PythonJson.MAPPER.readValue(
            stored, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      } catch (Exception e) {
        return new LinkedHashMap<>();
      }
    }

    @Override
    public String snapshotFor(String watchUuid, long timestamp) {
      return store.snapshot(watchUuid, timestamp);
    }

    @Override
    public Map<String, Object> priceAndStockFallback(Watch watch, String htmlContent) {
      return io.akka.changedetection.llm.RestockFallback.extract(
          store.llmSurroundings(),
          htmlContent,
          watch.link(text -> text),
          io.akka.changedetection.llm.Evaluator.resolveIntent(watch, store.llmSurroundings())[0]);
    }

    @Override
    public Map<String, Object> tagRestockOverride(Watch watch) {
      for (Map<String, Object> tag : store.tagsForWatch(watch).values()) {
        if (Fields.truthy(tag.get("overrides_watch"))) {
          Object config = tag.get("processor_config_restock_diff");
          if (config instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
          }
          return new LinkedHashMap<>();
        }
      }
      return null;
    }
  }
}
