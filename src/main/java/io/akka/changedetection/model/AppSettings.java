package io.akka.changedetection.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The settings that apply to every watch unless a watch or a tag overrides them. */
public final class AppSettings {

  public static final String DEFAULT_USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/87.0.4280.66 Safari/537.36";

  public static final int FILTER_FAILURE_THRESHOLD_DEFAULT = 6;

  public static final String DEFAULT_NOTIFICATION_FORMAT = "htmlcolor";
  public static final String DEFAULT_NOTIFICATION_BODY =
      "{{watch_url}} had a change.\n---\n{{diff}}\n---\n";
  public static final String DEFAULT_NOTIFICATION_TITLE =
      "ChangeDetection.io Notification - {{watch_url}}";

  public static final String RSS_CONTENT_FORMAT_DEFAULT = "text";
  public static final String RSS_TEMPLATE_PLAINTEXT_DEFAULT =
      "<pre>{{watch_label}} had a change.\n\n{{diff}}\n</pre>";
  public static final String RSS_TEMPLATE_HTML_DEFAULT =
      "<html><body>\n<h4><a href=\"{{watch_url}}\">{{watch_label}}</a></h4>\n"
          + "<p>{{diff}}</p>\n</body></html>\n";

  /** The formats a notification may be sent in. */
  public static final Map<String, String> NOTIFICATION_FORMATS = new LinkedHashMap<>();

  static {
    NOTIFICATION_FORMATS.put("text", "Plain Text");
    NOTIFICATION_FORMATS.put("html", "HTML");
    NOTIFICATION_FORMATS.put("htmlcolor", "HTML Color");
    NOTIFICATION_FORMATS.put("markdown", "Markdown to HTML");
    NOTIFICATION_FORMATS.put(
        Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT,
        Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT);
  }

  /** The formats a feed may be written in, which are the notification formats less two. */
  public static Map<String, String> rssFormats() {
    Map<String, String> formats = new LinkedHashMap<>(NOTIFICATION_FORMATS);
    formats.remove("markdown");
    formats.remove(Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT);
    return formats;
  }

  private AppSettings() {}

  /**
   * A fresh secret, made when the settings are first created.
   *
   * <p>Both the feeds and the programmatic interface are closed until one exists, so an
   * installation that started without them would refuse every caller and every feed reader --
   * which reads as the service being broken rather than as a setting nobody set.
   */
  static String newToken() {
    byte[] bytes = new byte[16];
    new java.security.SecureRandom().nextBytes(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte value : bytes) {
      hex.append(String.format("%02x", value));
    }
    return hex.toString();
  }

  public static Map<String, Object> create() {
    Map<String, Object> requests = new LinkedHashMap<>();
    requests.put("extra_proxies", new ArrayList<>());
    requests.put("extra_browsers", new ArrayList<>());
    requests.put("jitter_seconds", 0);
    requests.put("proxy", null);
    Map<String, Object> interval = WatchDefaults.defaultTimeBetweenCheck();
    interval.put("hours", 3);
    requests.put("time_between_check", interval);
    requests.put("timeout", envInt("DEFAULT_SETTINGS_REQUESTS_TIMEOUT", 45));
    requests.put("workers", envInt("DEFAULT_SETTINGS_REQUESTS_WORKERS", 5));
    Map<String, Object> defaultUa = new LinkedHashMap<>();
    defaultUa.put("html_requests", env("DEFAULT_SETTINGS_HEADERS_USERAGENT", DEFAULT_USER_AGENT));
    defaultUa.put("html_webdriver", null);
    requests.put("default_ua", defaultUa);
    requests.put("time_schedule_limit", WatchDefaults.defaultSchedule());

    Map<String, Object> ui = new LinkedHashMap<>();
    ui.put("use_page_title_in_list", true);
    ui.put("open_diff_in_new_tab", true);
    ui.put("socket_io_enabled", true);
    ui.put("favicons_enabled", true);
    ui.put("timeago_format", "long");
    ui.put("sidebar_mode", "collapsed");

    Map<String, Object> application = new LinkedHashMap<>();
    application.put("all_paused", false);
    application.put("all_muted", false);
    application.put("api_access_token_enabled", true);
    application.put("base_url", null);
    application.put("empty_pages_are_a_change", false);
    application.put("fetch_backend", env("DEFAULT_FETCH_BACKEND", "html_requests"));
    application.put(
        "filter_failure_notification_threshold_attempts", FILTER_FAILURE_THRESHOLD_DEFAULT);
    application.put("global_ignore_text", new ArrayList<>());
    application.put("global_subtractive_selectors", new ArrayList<>());
    application.put("history_snapshot_max_length", null);
    application.put("ignore_whitespace", true);
    application.put("ignore_status_codes", false);
    application.put("ssim_threshold", "0.96");
    application.put("notification_body", DEFAULT_NOTIFICATION_BODY);
    application.put("notification_format", DEFAULT_NOTIFICATION_FORMAT);
    application.put("notification_title", DEFAULT_NOTIFICATION_TITLE);
    application.put("notification_urls", new ArrayList<>());
    application.put("pager_size", 50);
    application.put("password", false);
    application.put("render_anchor_tag_content", false);
    application.put("rss_access_token", newToken());
    application.put("rss_content_format", RSS_CONTENT_FORMAT_DEFAULT);
    application.put("rss_template_type", "system_default");
    application.put("rss_template_override", null);
    application.put("rss_diff_length", 5);
    application.put("rss_hide_muted_watches", true);
    application.put("rss_reader_mode", false);
    application.put("scheduler_timezone_default", null);
    application.put("schema_version", 0);
    application.put("shared_diff_access", false);
    application.put("strip_ignored_lines", false);
    application.put("tags", new LinkedHashMap<String, Object>());
    application.put("webdriver_delay", null);
    application.put("ui", ui);
    application.put("api_access_token", newToken());

    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("headers", new LinkedHashMap<String, Object>());
    settings.put("requests", requests);
    settings.put("application", application);

    Map<String, Object> root = new LinkedHashMap<>();
    root.put(
        "note",
        "Hello! If you change this file manually, please be sure to restart your "
            + "changedetection.io instance!");
    root.put("watching", new LinkedHashMap<String, Object>());
    root.put("settings", settings);
    return root;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static int envInt(String name, int fallback) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** The list of watch fields a tag may carry as an override. */
  public static List<String> overridableAttributes() {
    return List.of(
        "include_filters", "subtractive_selectors", "extract_lines_containing", "extract_text",
        "ignore_text", "trigger_text", "text_should_not_be_present");
  }
}
