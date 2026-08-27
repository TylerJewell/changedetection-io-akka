package io.akka.changedetection.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings for the AI evaluation, which live under the application settings.
 *
 * <p>Stored as a plain map like the rest of the settings, but read through here so that a key
 * that was never declared cannot arrive from a form submission. A submission that could invent
 * settings keys could raise its own token ceiling or point the evaluation at another endpoint,
 * so an unknown key is dropped rather than stored.
 */
public final class LlmSettings {

  public static final int DEFAULT_THINKING_BUDGET = 0;
  public static final int DEFAULT_MAX_SUMMARY_TOKENS = 3000;
  public static final int DEFAULT_LOCAL_TOKEN_MULTIPLIER = 5;
  public static final int DEFAULT_MAX_INPUT_CHARS = 100_000;
  public static final String DEFAULT_BUDGET_ACTION = "skip_llm";

  public static final String PROMPT_MODE_REPLACE = "replace";
  public static final String PROMPT_MODE_APPEND = "append";

  /** Wiped when the operator clears the provider, or empties the model. */
  public static final List<String> CONNECTION_FIELDS =
      List.of("model", "api_key", "api_base", "provider_kind", "local_token_multiplier");

  /** Counters the runtime owns; a form submission must never write them. */
  public static final List<String> PROTECTED_FIELDS =
      List.of(
          "tokens_total_cumulative",
          "tokens_this_month",
          "tokens_month_key",
          "cost_usd_total_cumulative",
          "cost_usd_this_month");

  private LlmSettings() {}

  public static Map<String, Object> defaults() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", true);
    values.put("debug", false);
    values.put("override_diff_with_summary", true);
    values.put("restock_use_fallback_extract", true);
    values.put("thinking_budget", DEFAULT_THINKING_BUDGET);
    values.put("max_summary_tokens", DEFAULT_MAX_SUMMARY_TOKENS);
    values.put("budget_action", DEFAULT_BUDGET_ACTION);
    values.put("watchlist_overview_summary", "second_last_version");
    values.put("change_summary_default", "");
    values.put("token_budget_month", 0);
    values.put("max_input_chars", DEFAULT_MAX_INPUT_CHARS);
    values.put("max_tokens_per_count_period", 0);
    values.put("model", "");
    values.put("api_key", "");
    values.put("api_base", "");
    values.put("provider_kind", "");
    values.put("local_token_multiplier", DEFAULT_LOCAL_TOKEN_MULTIPLIER);
    values.put("tokens_total_cumulative", 0);
    values.put("tokens_this_month", 0);
    values.put("tokens_month_key", "");
    values.put("cost_usd_total_cumulative", 0.0);
    values.put("cost_usd_this_month", 0.0);
    return values;
  }

  /** The stored settings filled out with the defaults for anything absent. */
  public static Map<String, Object> of(Map<String, Object> application) {
    Map<String, Object> values = defaults();
    Object stored = application == null ? null : application.get("llm");
    if (stored instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (values.containsKey(key)) {
          values.put(key, entry.getValue());
        }
      }
    }
    return values;
  }

  /** The settings a submission may set, which is everything except the runtime's counters. */
  public static Map<String, Object> merge(
      Map<String, Object> existing, Map<String, Object> submitted) {
    Map<String, Object> values = new LinkedHashMap<>(existing);
    Map<String, Object> declared = defaults();
    for (Map.Entry<String, Object> entry : submitted.entrySet()) {
      String key = entry.getKey();
      if (!declared.containsKey(key) || PROTECTED_FIELDS.contains(key)) {
        continue;
      }
      values.put(key, entry.getValue());
    }
    return values;
  }

  public static boolean configured(Map<String, Object> llm) {
    Object model = llm.get("model");
    return model != null && !String.valueOf(model).isBlank();
  }
}
