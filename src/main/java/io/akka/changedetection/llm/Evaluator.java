package io.akka.changedetection.llm;

import io.akka.changedetection.model.LlmSettings;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.model.Watch;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deciding what a change means, and describing it.
 *
 * <p>Three things happen here and they are deliberately separate: deciding whether a change is
 * one the operator asked about, writing a plain-language description of it, and answering a
 * question against the current page. Each is advisory -- a model that is unreachable, over
 * budget, or wrong must never cause a change to go unreported, so every failure path here
 * passes the change through as important rather than suppressing it.
 */
public final class Evaluator {

  /** What the evaluation needs from the rest of the system. */
  public interface Surroundings {
    /** The application settings, which carry the AI settings and their counters. */
    Map<String, Object> application();

    /** Every group, by identifier. */
    Map<String, Map<String, Object>> tags();

    /** Stores the AI settings back, counters and all. */
    void saveLlmSettings(Map<String, Object> llm);
  }

  private Evaluator() {}

  /** Raised when what would be sent is larger than the operator allowed. */
  public static class InputTooLarge extends RuntimeException {
    public InputTooLarge(String message) {
      super(message);
    }
  }

  // ------------------------------------------------------------------ settings

  public static boolean featuresDisabledByEnvironment() {
    return truthy(System.getenv("LLM_FEATURES_DISABLED"));
  }

  public static Map<String, Object> settings(Surroundings around) {
    return LlmSettings.of(around.application());
  }

  /**
   * The provider to call, or null when none is configured.
   *
   * <p>The environment wins over the stored settings, so an operator can point a container at a
   * model without the settings page being reachable at all.
   */
  public static Map<String, Object> config(Surroundings around) {
    if (featuresDisabledByEnvironment()) {
      return null;
    }
    String envModel = env("LLM_MODEL");
    if (!envModel.isEmpty()) {
      Map<String, Object> config = new LinkedHashMap<>();
      config.put("model", envModel);
      config.put("api_key", env("LLM_API_KEY"));
      config.put("api_base", env("LLM_API_BASE"));
      return config;
    }
    Map<String, Object> stored = settings(around);
    if (String.valueOf(stored.getOrDefault("model", "")).isEmpty()) {
      return null;
    }
    return stored;
  }

  public static boolean configuredByEnvironment() {
    return !featuresDisabledByEnvironment() && !env("LLM_MODEL").isEmpty();
  }

  /** The provider to call, and only when the operator has not switched the whole thing off. */
  private static Map<String, Object> runtimeConfig(Surroundings around) {
    Map<String, Object> config = config(around);
    Object enabled = settings(around).get("enabled");
    if (!truthy(enabled)) {
      return null;
    }
    return config;
  }

  public static int maxInputChars(Surroundings around) {
    String configured = env("LLM_MAX_INPUT_CHARS");
    if (configured.matches("\\d+") && Integer.parseInt(configured) > 0) {
      return Integer.parseInt(configured);
    }
    Object stored = settings(around).get("max_input_chars");
    long value = stored instanceof Number number ? number.longValue() : 0;
    return value > 0 ? (int) value : LlmSettings.DEFAULT_MAX_INPUT_CHARS;
  }

  private static void checkInputSize(String text, int maxChars) {
    if (text.length() > maxChars) {
      throw new InputTooLarge(
          "Change too large for AI summary ("
              + group(text.length())
              + " chars, limit "
              + group(maxChars)
              + ")");
    }
  }

  private static String group(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  // ------------------------------------------------------------------ token caps

  static int summaryMaxTokens(String diff, int cap) {
    return Math.max(400, Math.min(diff.length() / 4, cap));
  }

  /**
   * Room for a model that thinks before it answers.
   *
   * <p>Only for the endpoints that commonly serve one. A reasoning model emits its working
   * before the answer, so a cap sized for the answer alone truncates it mid-thought and the
   * answer never arrives at all -- which reads to the caller as an empty reply rather than as
   * a failure.
   */
  public static int applyLocalTokenMultiplier(int baseMaxTokens, Map<String, Object> config) {
    Object kind = config == null ? null : config.get("provider_kind");
    String name = kind == null ? "" : String.valueOf(kind);
    if (!name.equals("ollama") && !name.equals("openai_compatible")) {
      return baseMaxTokens;
    }
    int multiplier = 5;
    Object configured = config.get("local_token_multiplier");
    if (configured instanceof Number number) {
      multiplier = number.intValue();
    } else if (configured != null) {
      try {
        multiplier = Integer.parseInt(String.valueOf(configured).strip());
      } catch (NumberFormatException e) {
        multiplier = 5;
      }
    }
    // Clamped to the range the form enforces, because the settings can also be edited by hand.
    multiplier = Math.max(1, Math.min(multiplier, 20));
    return baseMaxTokens * multiplier;
  }

  static boolean isLocalEndpoint(Map<String, Object> config) {
    String apiBase =
        config == null ? "" : String.valueOf(config.getOrDefault("api_base", "")).strip();
    if (apiBase.isEmpty() || apiBase.equals("null")) {
      return false;
    }
    for (String hostname : UrlSafety.hostnames(apiBase)) {
      if (UrlSafety.whyHostIsRefused(hostname) != null) {
        return true;
      }
    }
    return false;
  }

  public static int resolveTimeout(Map<String, Object> config) {
    if (!env("LLM_TIMEOUT").isEmpty()) {
      return LlmClient.DEFAULT_TIMEOUT;
    }
    return isLocalEndpoint(config) ? LlmClient.DEFAULT_LOCAL_TIMEOUT : LlmClient.DEFAULT_TIMEOUT;
  }

  // ------------------------------------------------------------------ cascades

  /** A watch's own value, else the first group that has one. */
  public static String[] resolveField(Watch watch, Surroundings around, String field) {
    String own = watch.fields().string(field, "").strip();
    if (!own.isEmpty() && !own.equals("null")) {
      return new String[] {own, "watch"};
    }
    for (Object identifier : watch.fields().strings("tags")) {
      Map<String, Object> tag = around.tags().get(String.valueOf(identifier));
      if (tag == null) {
        continue;
      }
      String value = String.valueOf(tag.getOrDefault(field, "")).strip();
      if (!value.isEmpty() && !value.equals("null")) {
        return new String[] {value, String.valueOf(tag.getOrDefault("title", "tag"))};
      }
    }
    return new String[] {"", ""};
  }

  public static String[] resolveIntent(Watch watch, Surroundings around) {
    return resolveField(watch, around, "llm_intent");
  }

  private static Map<String, Object> firstTagWith(
      Watch watch, Surroundings around, String field, String[] valueOut) {
    for (Object identifier : watch.fields().strings("tags")) {
      Map<String, Object> tag = around.tags().get(String.valueOf(identifier));
      if (tag == null) {
        continue;
      }
      String value = String.valueOf(tag.getOrDefault(field, "")).strip();
      if (!value.isEmpty() && !value.equals("null")) {
        valueOut[0] = value;
        return tag;
      }
    }
    valueOut[0] = "";
    return null;
  }

  /**
   * One level of the prompt cascade folded onto what it inherited.
   *
   * <p>Adding rather than replacing lets a watch contribute a sentence without pinning a copy
   * of the whole prompt, so a later edit to the shared one still reaches it.
   */
  static String applyPromptLayer(String inherited, String value, String mode) {
    if (value == null || value.isEmpty()) {
      return inherited;
    }
    if (LlmSettings.PROMPT_MODE_APPEND.equals(mode) && !inherited.isEmpty()) {
      return inherited + "\n\n" + value;
    }
    return value;
  }

  public static String effectiveSummaryPrompt(Watch watch, Surroundings around) {
    String configured =
        String.valueOf(settings(around).getOrDefault("change_summary_default", "")).strip();
    String prompt =
        configured.isEmpty() ? PromptBuilder.DEFAULT_CHANGE_SUMMARY_PROMPT : configured;

    String[] tagValue = new String[1];
    Map<String, Object> tag = firstTagWith(watch, around, "llm_change_summary", tagValue);
    if (tag != null) {
      prompt =
          applyPromptLayer(
              prompt,
              tagValue[0],
              String.valueOf(tag.getOrDefault("llm_change_summary_mode", "")));
    }
    String own = String.valueOf(watch.fields().string("llm_change_summary", "")).strip();
    if (!own.isEmpty() && !own.equals("null")) {
      prompt =
          applyPromptLayer(
              prompt,
              own,
              String.valueOf(watch.fields().string("llm_change_summary_mode", "")));
    }
    return prompt;
  }

  /** What a shown difference was asked to look like, which is part of a summary's identity. */
  public record DiffPrefs(
      boolean allChanges, boolean ignoreWhitespace, boolean showRemoved, boolean showAdded) {
    public static DiffPrefs standard() {
      return new DiffPrefs(false, false, true, true);
    }

    public String cacheKeySuffix() {
      return "\0prefs:all="
          + (allChanges ? 1 : 0)
          + ",ws="
          + (ignoreWhitespace ? 1 : 0)
          + ",rm="
          + (showRemoved ? 1 : 0)
          + ",add="
          + (showAdded ? 1 : 0);
    }
  }

  /**
   * The whole string a cached summary is keyed by.
   *
   * <p>The model is folded in so that changing it does not leave summaries written by the old
   * one lying around, and the display settings are folded in because a summary of "only the
   * added lines" is not a summary of the same change.
   */
  public static String summaryCachePrompt(
      String effectivePrompt, int maxSummaryTokens, DiffPrefs prefs, String model) {
    DiffPrefs settings = prefs == null ? DiffPrefs.standard() : prefs;
    return effectivePrompt
        + settings.cacheKeySuffix()
        + "\0sys:"
        + PromptBuilder.changeSummarySystemPrompt()
        + "\0max_tokens:"
        + maxSummaryTokens
        + "\0model:"
        + model;
  }

  public static String summaryCacheKey(String diffText, String prompt) {
    return hexDigest("MD5", diffText + "\0" + prompt).substring(0, 16);
  }

  // ------------------------------------------------------------------ budgets

  static String monthKey() {
    return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
  }

  public static long globalTokenBudget(Surroundings around) {
    String configured = env("LLM_TOKEN_BUDGET_MONTH");
    try {
      long value = configured.isEmpty() ? 0 : Long.parseLong(configured.strip());
      if (value > 0) {
        return value;
      }
    } catch (NumberFormatException e) {
      // Not a number: fall through to the stored setting, as the original does.
    }
    if (around == null) {
      return 0;
    }
    Object stored = settings(around).get("token_budget_month");
    long value = stored instanceof Number number ? number.longValue() : 0;
    return Math.max(0, value);
  }

  public static boolean globalBudgetExceeded(Surroundings around) {
    long budget = globalTokenBudget(around);
    if (budget == 0) {
      return false;
    }
    Map<String, Object> llm = settings(around);
    if (!monthKey().equals(String.valueOf(llm.getOrDefault("tokens_month_key", "")))) {
      return false;
    }
    Object used = llm.get("tokens_this_month");
    return (used instanceof Number number ? number.longValue() : 0) >= budget;
  }

  /**
   * Adds a call's tokens to the running totals.
   *
   * <p>Written only here. The counters are what a budget is checked against, so a form
   * submission that could set them would be a way to reset the budget.
   */
  public static void accumulate(
      Surroundings around, int tokens, int inputTokens, int outputTokens, String model) {
    if (tokens <= 0) {
      return;
    }
    Map<String, Object> llm = settings(around);
    String currentMonth = monthKey();
    double cost = estimateCostUsd(model, inputTokens, outputTokens);

    if (!currentMonth.equals(String.valueOf(llm.getOrDefault("tokens_month_key", "")))) {
      llm.put("tokens_this_month", 0L);
      llm.put("cost_usd_this_month", 0.0);
      llm.put("tokens_month_key", currentMonth);
    }
    llm.put("tokens_total_cumulative", asLong(llm.get("tokens_total_cumulative")) + tokens);
    llm.put("tokens_this_month", asLong(llm.get("tokens_this_month")) + tokens);
    llm.put(
        "cost_usd_total_cumulative", asDouble(llm.get("cost_usd_total_cumulative")) + cost);
    llm.put("cost_usd_this_month", asDouble(llm.get("cost_usd_this_month")) + cost);
    around.saveLlmSettings(llm);
  }

  /**
   * What a call is likely to have cost.
   *
   * <p>Best effort and never fatal. A model this rebuild has no price for -- a local one, a
   * newly released one -- contributes nothing rather than a guess, because a made-up figure in
   * a running total is worse than no figure at all.
   */
  public static double estimateCostUsd(String model, int inputTokens, int outputTokens) {
    if (model == null || model.isEmpty() || (inputTokens == 0 && outputTokens == 0)) {
      return 0.0;
    }
    double[] rate = Pricing.perMillionTokens(model);
    if (rate == null) {
      return 0.0;
    }
    return inputTokens * rate[0] / 1_000_000.0 + outputTokens * rate[1] / 1_000_000.0;
  }

  /**
   * A watch's own cap for the period, and the counters behind it.
   *
   * @return false once the watch has spent more than it was allowed this period
   */
  public static boolean checkTokenBudget(Watch watch, Map<String, Object> config, int tokens) {
    if (tokens > 0) {
      String currentPeriod = monthKey();
      Map<String, Object> changes = new LinkedHashMap<>();
      long spent;
      if (!currentPeriod.equals(
          String.valueOf(watch.fields().string("llm_tokens_period_key", "")))) {
        spent = 0;
        changes.put("llm_tokens_period_key", currentPeriod);
      } else {
        spent = asLong(watch.fields().get("llm_tokens_this_period"));
      }
      changes.put("llm_tokens_this_period", spent + tokens);
      changes.put(
          "llm_tokens_used_cumulative",
          asLong(watch.fields().get("llm_tokens_used_cumulative")) + tokens);
      watch.updateSystem(changes);
    }

    long maxPerPeriod = asLong(config == null ? null : config.get("max_tokens_per_count_period"));
    if (maxPerPeriod > 0
        && monthKey()
            .equals(String.valueOf(watch.fields().string("llm_tokens_period_key", "")))) {
      long total = asLong(watch.fields().get("llm_tokens_this_period"));
      return total <= maxPerPeriod;
    }
    return true;
  }

  // ------------------------------------------------------------------ the calls

  /** Asks whether a filter would make the evaluation sharper, and stores the answer. */
  public static void runSetup(Watch watch, Surroundings around, String snapshotText) {
    Map<String, Object> config = runtimeConfig(around);
    if (config == null) {
      return;
    }
    String intent = resolveIntent(watch, around)[0];
    if (intent.isEmpty()) {
      return;
    }
    Map<String, Object> llm = settings(around);
    try {
      LlmClient.Completion completion =
          call(
              config,
              llm,
              PromptBuilder.setupSystemPrompt(),
              PromptBuilder.setupPrompt(
                  intent, snapshotText, String.valueOf(watch.fields().string("url", ""))),
              applyLocalTokenMultiplier(LlmClient.JSON_RESPONSE_MAX_TOKENS, config));
      checkTokenBudget(watch, config, completion.totalTokens());
      accumulate(around, completion.totalTokens(), 0, 0, model(config));
      Map<String, Object> result = ResponseParser.parseSetup(completion.text());
      watch.updateSystem(Map.of("llm_prefilter", nullable(result.get("selector"))));
    } catch (RuntimeException e) {
      watch.updateSystem(mapWithNull("llm_prefilter"));
    }
  }

  /** Writes a plain-language description of a change; the empty string when it cannot. */
  public static String summariseChange(
      Watch watch, Surroundings around, String diff, String currentSnapshot) {
    Map<String, Object> config = runtimeConfig(around);
    if (config == null) {
      return "";
    }
    if (globalBudgetExceeded(around)) {
      return "";
    }
    String customPrompt = effectiveSummaryPrompt(watch, around);
    if (diff.strip().isEmpty()) {
      return "";
    }
    checkInputSize(diff, maxInputChars(around));

    Map<String, Object> llm = settings(around);
    int cap = (int) asLong(llm.getOrDefault("max_summary_tokens",
        LlmSettings.DEFAULT_MAX_SUMMARY_TOKENS));
    LlmClient.Completion completion =
        call(
            config,
            llm,
            PromptBuilder.changeSummarySystemPrompt(),
            PromptBuilder.changeSummaryPrompt(
                diff,
                customPrompt,
                currentSnapshot,
                String.valueOf(watch.fields().string("url", "")),
                titleOf(watch)),
            applyLocalTokenMultiplier(summaryMaxTokens(diff, cap), config));

    String summary = completion.text().strip();
    checkTokenBudget(watch, config, completion.totalTokens());
    watch.updateSystem(
        Map.of(
            "llm_last_tokens_used", completion.totalTokens(),
            "llm_tokens_used_cumulative",
                asLong(watch.fields().get("llm_tokens_used_cumulative"))
                    + completion.totalTokens()));
    accumulate(
        around,
        completion.totalTokens(),
        completion.inputTokens(),
        completion.outputTokens(),
        model(config));
    return summary;
  }

  /** Answers the watch's question against the page as it stands now. */
  public static Map<String, Object> previewExtract(
      Watch watch, Surroundings around, String content) {
    Map<String, Object> config = runtimeConfig(around);
    if (config == null) {
      return null;
    }
    String intent = resolveIntent(watch, around)[0];
    if (intent.isEmpty() || content.strip().isEmpty()) {
      return null;
    }
    checkInputSize(content, maxInputChars(around));
    Map<String, Object> llm = settings(around);
    try {
      LlmClient.Completion completion =
          call(
              config,
              llm,
              PromptBuilder.previewSystemPrompt(),
              PromptBuilder.previewPrompt(
                  intent,
                  content,
                  String.valueOf(watch.fields().string("url", "")),
                  titleOf(watch)),
              applyLocalTokenMultiplier(LlmClient.JSON_RESPONSE_MAX_TOKENS, config));
      accumulate(around, completion.totalTokens(), 0, 0, model(config));
      return ResponseParser.parsePreview(completion.text());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Decides whether a change is one the operator asked about.
   *
   * <p>Every way this can go wrong answers "important", so a model that is down, over budget or
   * unparseable delays nothing: the operator gets the notification they would have got had they
   * never configured an intent at all.
   */
  public static Map<String, Object> evaluateChange(
      Watch watch, Surroundings around, String diff, String currentSnapshot) {
    Map<String, Object> config = runtimeConfig(around);
    if (config == null) {
      return null;
    }
    String[] resolved = resolveIntent(watch, around);
    String intent = resolved[0];
    if (intent.isEmpty()) {
      return null;
    }
    if (diff == null || diff.strip().isEmpty()) {
      return important(false, "");
    }
    checkInputSize(diff, maxInputChars(around));

    String cacheKey = hexDigest("SHA-256", intent + "||" + diff);
    Map<String, Object> cache = evaluationCache(watch);
    if (cache.containsKey(cacheKey)) {
      Object cached = cache.get(cacheKey);
      if (cached instanceof Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
      }
    }

    if (globalBudgetExceeded(around)) {
      return important(true, "");
    }
    if (!checkTokenBudget(watch, config, 0)) {
      return important(true, "");
    }

    Map<String, Object> llm = settings(around);
    LlmClient.Completion completion;
    Map<String, Object> result;
    try {
      completion =
          call(
              config,
              llm,
              PromptBuilder.evalSystemPrompt(),
              PromptBuilder.evalPrompt(
                  intent,
                  diff,
                  currentSnapshot,
                  String.valueOf(watch.fields().string("url", "")),
                  titleOf(watch)),
              applyLocalTokenMultiplier(LlmClient.JSON_RESPONSE_MAX_TOKENS, config));
      result = ResponseParser.parseEval(completion.text());
    } catch (RuntimeException e) {
      watch.updateSystem(Map.of("llm_last_tokens_used", 0));
      return important(true, "");
    }

    checkTokenBudget(watch, config, completion.totalTokens());
    watch.updateSystem(Map.of("llm_last_tokens_used", completion.totalTokens()));
    accumulate(
        around,
        completion.totalTokens(),
        completion.inputTokens(),
        completion.outputTokens(),
        model(config));

    cache.put(cacheKey, result);
    watch.updateSystem(Map.of("llm_evaluation_cache", cache));
    return result;
  }

  // ------------------------------------------------------------------ plumbing

  private static LlmClient.Completion call(
      Map<String, Object> config,
      Map<String, Object> llm,
      String systemPrompt,
      String userPrompt,
      int maxTokens) {
    LlmClient.Request request = new LlmClient.Request();
    request.model = model(config);
    request.messages =
        List.of(
            new LlmClient.Message("system", systemPrompt),
            new LlmClient.Message("user", userPrompt));
    request.apiKey = String.valueOf(config.getOrDefault("api_key", ""));
    request.apiBase = String.valueOf(config.getOrDefault("api_base", ""));
    request.timeoutSeconds = resolveTimeout(config);
    request.maxTokens = maxTokens;
    request.extraBody =
        LlmClient.thinkingBudget(
            request.model,
            (int) asLong(llm.getOrDefault("thinking_budget",
                LlmSettings.DEFAULT_THINKING_BUDGET)));
    request.debug = truthy(llm.get("debug"));
    if ("null".equals(request.apiKey)) {
      request.apiKey = "";
    }
    if ("null".equals(request.apiBase)) {
      request.apiBase = "";
    }
    return LlmClient.completion(request);
  }

  private static String model(Map<String, Object> config) {
    return String.valueOf(config.getOrDefault("model", ""));
  }

  private static String titleOf(Watch watch) {
    String pageTitle = String.valueOf(watch.fields().string("page_title", ""));
    if (!pageTitle.isEmpty() && !pageTitle.equals("null")) {
      return pageTitle;
    }
    String title = String.valueOf(watch.fields().string("title", ""));
    return title.equals("null") ? "" : title;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> evaluationCache(Watch watch) {
    Object cache = watch.fields().get("llm_evaluation_cache");
    if (cache instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return new LinkedHashMap<>();
  }

  private static Map<String, Object> important(boolean value, String summary) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("important", value);
    result.put("summary", summary);
    return result;
  }

  private static Map<String, Object> mapWithNull(String key) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(key, null);
    return out;
  }

  private static Object nullable(Object value) {
    return value;
  }

  public static String hexDigest(String algorithm, String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is unavailable", e);
    }
  }

  private static long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return value == null ? 0 : Long.parseLong(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static double asDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return value == null ? 0 : Double.parseDouble(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String env(String variable) {
    String value = System.getenv(variable);
    return value == null ? "" : value.strip();
  }

  private static boolean truthy(Object value) {
    if (value instanceof Boolean flag) {
      return flag;
    }
    if (value == null) {
      return false;
    }
    String lower = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
    return lower.equals("y")
        || lower.equals("yes")
        || lower.equals("t")
        || lower.equals("true")
        || lower.equals("on")
        || lower.equals("1");
  }

  /** Only the names needed by the pieces above, kept together for readability. */
  static List<String> promptFields() {
    return new ArrayList<>(List.of("llm_intent", "llm_change_summary"));
  }
}
