package io.akka.changedetection.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The name every route is known by in the templates, and the path it stands for.
 *
 * <p>The shipped templates never write a path; they name a route and let the server build it.
 * Keeping the names is what lets the templates be shipped unchanged, and it is also the reason
 * the paths here are the original's exactly -- a bookmark, a feed reader's stored address and a
 * script's fetch all point at these.
 */
public final class Routes {

  private static final Map<String, String> PATHS = new LinkedHashMap<>();

  static {
    PATHS.put("watchlist.index", "/");
    PATHS.put("watchlist.uuids", "/uuids");

    PATHS.put("login", "/login");
    PATHS.put("logout", "/logout");
    PATHS.put("set_language", "/set-language/{locale}");
    PATHS.put("static_content", "/static/{group}/{filename}");
    PATHS.put("static_flags", "/static/flags/{flag_path}");
    PATHS.put("gc_cleanup", "/gc-cleanup");
    PATHS.put("worker_health", "/worker-health");
    PATHS.put("queue_status", "/queue-status");

    PATHS.put("ui.clear_watch_history", "/clear_history/{uuid}");
    PATHS.put("ui.clear_all_history", "/clear_history");
    PATHS.put("ui.mark_all_viewed", "/form/mark-all-viewed");
    PATHS.put("ui.form_delete", "/delete");
    PATHS.put("ui.form_clone", "/clone");
    PATHS.put("ui.form_watch_checknow", "/checknow");
    PATHS.put("ui.form_watch_list_checkbox_operations", "/form/checkbox-operations");
    PATHS.put("ui.form_share_put_watch", "/share-url/{uuid}");
    PATHS.put("ui.delete_locale_language_session_var_if_it_exists", "/language/auto-detect");

    PATHS.put("ui.ui_views.form_quick_watch_add", "/form/add/quickwatch");

    PATHS.put("ui.ui_edit.edit_page", "/edit/{uuid}");
    PATHS.put("ui.ui_edit.watch_get_latest_html", "/edit/{uuid}/get-html");
    PATHS.put("ui.ui_edit.watch_get_data_package", "/edit/{uuid}/get-data-package");
    PATHS.put("ui.ui_edit.watch_get_preview_rendered", "/edit/{uuid}/preview-rendered");
    PATHS.put("ui.ui_edit.highlight_submit_ignore_url", "/highlight_submit_ignore_url");

    PATHS.put("ui.ui_diff.diff_history_page", "/diff/{uuid}");
    PATHS.put("ui.ui_diff.diff_llm_summary_prompt", "/diff/{uuid}/llm-summary/prompt");
    PATHS.put("ui.ui_diff.diff_llm_summary", "/diff/{uuid}/llm-summary");
    PATHS.put("ui.ui_diff.diff_history_page_processor_data", "/diff/{uuid}/processor-data");
    PATHS.put(
        "ui.ui_diff.diff_history_page_processor_export", "/diff/{uuid}/processor-export.xlsx");
    PATHS.put("ui.ui_diff.diff_history_page_extract_GET", "/diff/{uuid}/extract");
    PATHS.put("ui.ui_diff.diff_history_page_extract_POST", "/diff/{uuid}/extract");
    PATHS.put("ui.ui_diff.download_patch", "/diff/{uuid}/download-patch");
    PATHS.put("ui.ui_diff.processor_asset", "/diff/{uuid}/processor-asset/{asset_name}");

    PATHS.put("ui.ui_preview.preview_page", "/preview/{uuid}");
    PATHS.put("ui.ui_preview.processor_asset", "/preview/{uuid}/processor-asset/{asset_name}");

    PATHS.put("ui.ui_queue.queue_page", "/queue");
    PATHS.put("ui.ui_queue.queue_json", "/queue.json");
    PATHS.put("ui.ui_queue.queue_clear", "/queue/clear");
    PATHS.put("ui.ui_queue.queue_cancel_running", "/queue/cancel-running");

    PATHS.put(
        "ui.ui_notification.ajax_callback_send_notification_test",
        "/notification/send-test/{watch_uuid}");

    PATHS.put("tags.tags_overview_page", "/tags/list");
    PATHS.put("tags.form_tag_add", "/tags/add");
    PATHS.put("tags.mute", "/tags/mute/{uuid}");
    PATHS.put("tags.delete", "/tags/delete/{uuid}");
    PATHS.put("tags.unlink", "/tags/unlink/{uuid}");
    PATHS.put("tags.delete_all", "/tags/delete_all");
    PATHS.put("tags.form_tag_edit", "/tags/edit/{uuid}");
    PATHS.put("tags.form_tag_edit_submit", "/tags/edit/{uuid}");

    PATHS.put("settings.settings_page", "/settings");
    PATHS.put("settings.settings_reset_api_key", "/settings/reset-api-key");
    PATHS.put("settings.notification_logs", "/settings/notification-logs");
    PATHS.put("settings.toggle_all_paused", "/settings/toggle-all-paused");
    PATHS.put("settings.toggle_all_muted", "/settings/toggle-all-muted");
    PATHS.put("settings.notifications.index", "/settings/notifications/");
    PATHS.put("settings.notifications.apprise", "/settings/notifications/apprise");
    PATHS.put("settings.llm.llm_get_models", "/settings/llm/models");
    PATHS.put("settings.llm.llm_test", "/settings/llm/test");
    PATHS.put("settings.llm.llm_clear", "/settings/llm/clear");
    PATHS.put("settings.llm.llm_clear_summary_cache", "/settings/llm/clear-summary-cache");

    PATHS.put("backups.create", "/backups/");
    PATHS.put("backups.request_backup", "/backups/request-backup");
    PATHS.put("backups.download_backup", "/backups/download/{filename}");
    PATHS.put("backups.remove_backups", "/backups/remove-backups");
    PATHS.put("backups.restore.restore", "/backups/restore");
    PATHS.put("backups.restore.backups_restore_start", "/backups/restore/start");

    PATHS.put("imports.import_page", "/imports/import");

    PATHS.put("rss.feed", "/rss");
    PATHS.put("rss.extraslash", "/rss/");
    PATHS.put("rss.rss_single_watch", "/rss/watch/{uuid}");
    PATHS.put("rss.rss_tag_feed", "/rss/tag/{tag_uuid}");

    PATHS.put("browser_steps.browsersteps_start_session", "/browser-steps/browsersteps_start_session");
    PATHS.put(
        "browser_steps.browser_steps_fetch_screenshot_image", "/browser-steps/browsersteps_image");
    PATHS.put("browser_steps.browsersteps_ui_update", "/browser-steps/browsersteps_update");

    PATHS.put("check_proxies.get_recheck_status", "/check_proxy/{uuid}/status");
    PATHS.put("check_proxies.start_check", "/check_proxy/{uuid}/start");

    PATHS.put("price_data_follower.accept", "/price_data_follower/{uuid}/accept");
    PATHS.put("price_data_follower.reject", "/price_data_follower/{uuid}/reject");

    PATHS.put("add_watch_ui.add_watch_ui_index", "/add-watch-ui/");
    PATHS.put("add_watch_ui.add_watch_ui_snapshot", "/add-watch-ui/snapshot");
    PATHS.put("add_watch_ui.static", "/add-watch-ui/static/{filename}");

    PATHS.put(
        "conditions.verify_condition_single_rule",
        "/conditions/{watch_uuid}/verify-condition-single-rule");
  }

  private Routes() {}

  public static boolean knows(String name) {
    return PATHS.containsKey(name);
  }

  /**
   * Whether a path is one this interface answers.
   *
   * <p>Asked of a path a caller supplied, so a path the router would only match through the
   * shipped-file route does not count -- that route matches almost anything.
   */
  public static boolean matchesKnownRoute(String path) {
    if (path == null || path.isEmpty()) {
      return false;
    }
    String candidate = path;
    int hash = candidate.indexOf('#');
    if (hash >= 0) {
      candidate = candidate.substring(0, hash);
    }
    int question = candidate.indexOf('?');
    if (question >= 0) {
      candidate = candidate.substring(0, question);
    }
    for (Map.Entry<String, String> entry : PATHS.entrySet()) {
      if (entry.getKey().startsWith("static_")) {
        continue;
      }
      if (matches(entry.getValue(), candidate)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(String template, String candidate) {
    String[] wanted = template.split("/", -1);
    String[] given = candidate.split("/", -1);
    if (wanted.length != given.length) {
      return false;
    }
    for (int index = 0; index < wanted.length; index++) {
      String part = wanted[index];
      if (part.startsWith("{") && part.endsWith("}")) {
        if (given[index].isEmpty()) {
          return false;
        }
        continue;
      }
      if (!part.equals(given[index])) {
        return false;
      }
    }
    return true;
  }

  /**
   * The path for a named route.
   *
   * <p>Anything the path does not name is added as a query, which is how the templates pass
   * things like a version to compare against or a tag to filter by.
   */
  public static String build(String name, Map<String, Object> arguments) {
    String template = PATHS.get(name);
    if (template == null) {
      throw new IllegalArgumentException("no route named '" + name + "'");
    }
    Map<String, Object> remaining = new LinkedHashMap<>(arguments);
    StringBuilder path = new StringBuilder();
    int index = 0;
    while (index < template.length()) {
      char c = template.charAt(index);
      if (c != '{') {
        path.append(c);
        index++;
        continue;
      }
      int close = template.indexOf('}', index);
      String key = template.substring(index + 1, close);
      Object value = remaining.remove(key);
      path.append(value == null ? "" : encodePathSegment(String.valueOf(value)));
      index = close + 1;
    }

    List<String> query = new ArrayList<>();
    for (Map.Entry<String, Object> entry : remaining.entrySet()) {
      Object value = entry.getValue();
      if (value == null || Boolean.FALSE.equals(value)) {
        continue;
      }
      if (value instanceof Iterable<?> items) {
        for (Object item : items) {
          query.add(encode(entry.getKey()) + "=" + encode(String.valueOf(item)));
        }
        continue;
      }
      query.add(encode(entry.getKey()) + "=" + encode(String.valueOf(value)));
    }
    if (query.isEmpty()) {
      return path.toString();
    }
    return path + "?" + String.join("&", query);
  }

  /** A path segment, with the separator left alone so a nested name survives. */
  private static String encodePathSegment(String value) {
    StringBuilder sb = new StringBuilder();
    for (String part : value.split("/", -1)) {
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(encode(part).replace("+", "%20"));
    }
    return sb.toString();
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
