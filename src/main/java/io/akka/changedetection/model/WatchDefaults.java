package io.akka.changedetection.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every field a watch starts with, and what it starts as.
 *
 * <p>Most default to nothing set rather than to a value, and the distinction is load-bearing:
 * nothing set means the global setting applies, and a value means this watch overrides it.
 */
public final class WatchDefaults {

  private WatchDefaults() {}

  /** The names of the days, in the order the schedule holds them. */
  public static final List<String> DAYS =
      List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

  public static Map<String, Object> defaultSchedule() {
    Map<String, Object> schedule = new LinkedHashMap<>();
    schedule.put("enabled", false);
    for (String day : DAYS) {
      Map<String, Object> duration = new LinkedHashMap<>();
      duration.put("hours", "24");
      duration.put("minutes", "00");
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("enabled", true);
      entry.put("start_time", "00:00");
      entry.put("duration", duration);
      schedule.put(day, entry);
    }
    return schedule;
  }

  public static Map<String, Object> defaultTimeBetweenCheck() {
    Map<String, Object> interval = new LinkedHashMap<>();
    interval.put("weeks", null);
    interval.put("days", null);
    interval.put("hours", null);
    interval.put("minutes", null);
    interval.put("seconds", null);
    return interval;
  }

  /** The whole field set a fresh watch carries. */
  public static Map<String, Object> create(String uuid) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("body", null);
    f.put("browser_steps", new ArrayList<>());
    f.put("browser_steps_last_error_step", null);
    f.put("conditions", new ArrayList<>());
    f.put("conditions_match_logic", Fields.CONDITIONS_MATCH_LOGIC_DEFAULT);
    f.put("check_count", 0);
    f.put("check_unique_lines", false);
    f.put("consecutive_filter_failures", 0);
    f.put("content-type", null);
    f.put("date_created", null);
    f.put("extract_lines_containing", new ArrayList<>());
    f.put("extract_text", new ArrayList<>());
    f.put("llm_intent", "");
    f.put("llm_change_summary", "");
    f.put("llm_change_summary_mode", "replace");
    f.put("llm_prefilter", null);
    f.put("llm_evaluation_cache", new LinkedHashMap<>());
    f.put("fetch_backend", "system");
    f.put("fetch_time", 0.0);
    f.put("filter_failure_notification_send", true);
    f.put("filter_text_added", true);
    f.put("filter_text_removed", true);
    f.put("filter_text_replaced", true);
    f.put("follow_price_changes", true);
    f.put("has_ldjson_price_data", null);
    f.put("history_snapshot_max_length", null);
    f.put("headers", new LinkedHashMap<>());
    f.put("ignore_text", new ArrayList<>());
    f.put("ignore_status_codes", null);
    f.put("in_stock_only", true);
    f.put("include_filters", new ArrayList<>());
    f.put("last_checked", 0);
    f.put("last_error", false);
    f.put("last_notification_error", null);
    f.put("last_viewed", 0);
    f.put("method", "GET");
    f.put("notification_alert_count", 0);
    f.put("notification_body", null);
    f.put("notification_format", Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT);
    f.put("notification_muted", false);
    f.put("notification_screenshot", false);
    f.put("notification_title", null);
    f.put("notification_urls", new ArrayList<>());
    f.put("page_title", null);
    f.put("paused", false);
    f.put("previous_md5", false);
    f.put("processor", "text_json_diff");
    f.put("price_change_threshold_percent", null);
    f.put("proxy", null);
    f.put("remote_server_reply", null);
    f.put("sort_text_alphabetically", false);
    f.put("strip_ignored_lines", null);
    f.put("subtractive_selectors", new ArrayList<>());
    f.put("tag", "");
    f.put("tags", new ArrayList<>());
    f.put("text_should_not_be_present", new ArrayList<>());
    f.put("time_between_check", defaultTimeBetweenCheck());
    f.put("time_between_check_use_default", true);
    f.put("time_schedule_limit", defaultSchedule());
    f.put("title", null);
    f.put("track_ldjson_price_data", null);
    f.put("trim_text_whitespace", false);
    f.put("remove_duplicate_lines", false);
    f.put("trigger_text", new ArrayList<>());
    f.put("url", "");
    f.put("use_page_title_in_list", null);
    f.put("uuid", uuid);
    f.put("webdriver_delay", null);
    f.put("webdriver_js_execute_code", null);
    return f;
  }

  /**
   * The fields the system maintains rather than the operator, which therefore do not count as
   * an edit.
   *
   * <p>The distinction decides whether the next check may take the shortcut that skips
   * reprocessing when the fetched page has not changed: a watch whose configuration was edited
   * has to be reprocessed even when the page is identical, and a watch that merely recorded
   * when it last ran does not.
   */
  public static final List<String> SYSTEM_MANAGED =
      List.of(
          "check_count", "consecutive_filter_failures", "content-type", "date_created",
          "fetch_time", "has_ldjson_price_data", "last_check_status", "last_checked",
          "last_error", "last_filter_config_hash", "last_notification_error", "page_title",
          "previous_md5", "remote_server_reply", "restock", "uuid", "browser_steps_last_error_step",
          "notification_alert_count", "llm_evaluation_cache", "llm_last_tokens_used",
          "llm_tokens_used_cumulative");
}
