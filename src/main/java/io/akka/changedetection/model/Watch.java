package io.akka.changedetection.model;

import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.text.PythonText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One watched page: its configuration, its state, and the things derived from both. */
public final class Watch implements PyValue.Attributed {

  /** How the units of a check interval convert to seconds. */
  private static final Map<String, Long> INTERVAL_UNITS = new LinkedHashMap<>();

  static {
    INTERVAL_UNITS.put("weeks", 604800L);
    INTERVAL_UNITS.put("days", 86400L);
    INTERVAL_UNITS.put("hours", 3600L);
    INTERVAL_UNITS.put("minutes", 60L);
    INTERVAL_UNITS.put("seconds", 1L);
  }

  private final Fields fields;

  /**
   * The moments this watch has a stored snapshot for, oldest first.
   *
   * <p>Kept beside the configuration rather than inside it because it is derived from what has
   * been stored, not from what the operator set.
   */
  private final List<Long> history = new ArrayList<>();

  /**
   * Whether the operator changed anything since the last completed check.
   *
   * <p>The next check may skip all of its work when the fetched page is byte-identical to the
   * last one -- but only if the configuration is also unchanged, because a new filter has to be
   * applied to the same page. So this flag is the difference between a settings change taking
   * effect now and taking effect the next time the page happens to move.
   */
  private boolean edited;

  /** The spread applied to this watch's due moment, drawn when it first becomes due. */
  private double jitterSeconds;

  public Watch(Fields fields) {
    this.fields = fields;
  }

  public static Watch create(String uuid) {
    return new Watch(new Fields(WatchDefaults.create(uuid)));
  }

  public Fields fields() {
    return fields;
  }

  public Map<String, Object> asMap() {
    return fields.asMap();
  }

  public String uuid() {
    return fields.string("uuid");
  }

  public List<Long> history() {
    return history;
  }

  public int historyCount() {
    return history.size();
  }

  public void setHistory(List<Long> timestamps) {
    history.clear();
    history.addAll(timestamps);
  }

  public boolean wasEdited() {
    return edited;
  }

  public void resetEditedFlag() {
    edited = false;
  }

  public double jitterSeconds() {
    return jitterSeconds;
  }

  public void setJitterSeconds(double jitterSeconds) {
    this.jitterSeconds = jitterSeconds;
  }

  /**
   * Applies a set of changes, marking the watch edited when any of them is one the operator
   * could have made.
   */
  public void update(Map<String, Object> changes) {
    for (Map.Entry<String, Object> entry : changes.entrySet()) {
      String key = entry.getKey();
      fields.put(key, entry.getValue());
      if (!key.startsWith("_") && !WatchDefaults.SYSTEM_MANAGED.contains(key)
          && !key.equals("last_viewed")) {
        edited = true;
      }
    }
  }

  /** Applies changes without counting them as an edit, for what a check itself records. */
  public void updateSystem(Map<String, Object> changes) {
    fields.putAll(changes);
  }

  // ------------------------------------------------------------- properties

  /** The address to fetch, with any template in it rendered and the source marker removed. */
  public String link(java.util.function.Function<String, String> renderTemplate) {
    String url = fields.string("url", "");
    if (!UrlSafety.isSafeValidUrl(url, allowFileUri())) {
      return "DISABLED";
    }
    String ready = url;
    if (url.contains("{%") || url.contains("{{")) {
      try {
        ready = renderTemplate.apply(url);
      } catch (RuntimeException e) {
        return "";
      }
    }
    if (ready.startsWith("source:")) {
      ready = ready.substring("source:".length());
    }
    if (!UrlSafety.isSafeValidUrl(ready, allowFileUri())) {
      return "DISABLED";
    }
    return ready;
  }

  private static boolean allowFileUri() {
    return Fields.truthy(System.getenv("ALLOW_FILE_URI"));
  }

  /** True when the address asks for the page's own markup rather than its text. */
  public boolean isSourceTypeUrl() {
    return fields.string("url", "").startsWith("source:");
  }

  /**
   * True when the page is a document rather than markup.
   *
   * <p>Decided from the address and from what the server said it sent, because a document is
   * fetched rather than rendered whatever the watch's chosen fetcher is -- a browser would show
   * a viewer and there would be no text to compare.
   */
  public boolean isPdf() {
    String url = fields.string("url", "").toLowerCase(Locale.ROOT);
    String contentType = fields.string("content-type", "");
    contentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    if (contentType.equals("none") || contentType.equals("null")) {
      contentType = "";
    }
    String base = contentType.split(";")[0].strip();
    return url.endsWith(".pdf") || base.equals("application/pdf");
  }

  public String fetchBackend() {
    return isPdf() ? "html_requests" : fields.string("fetch_backend");
  }

  /** What the watch is called in a list: its own title, then the page's, then its address. */
  public String label(java.util.function.Function<String, String> renderTemplate) {
    String title = fields.string("title");
    if (title != null && !title.isEmpty()) {
      return title;
    }
    String pageTitle = fields.string("page_title");
    if (pageTitle != null && !pageTitle.isEmpty()) {
      return pageTitle;
    }
    return link(renderTemplate);
  }

  /** The newest stored moment, or zero when only one snapshot exists. */
  public long newestHistoryKey() {
    if (history.size() <= 1) {
      return 0;
    }
    return history.get(history.size() - 1);
  }

  /** When the page last actually changed, which a single snapshot does not count as. */
  public long lastChanged() {
    return history.size() <= 1 ? 0 : history.get(history.size() - 1);
  }

  public boolean viewed() {
    long lastViewed = fields.longValue("last_viewed", 0);
    return lastViewed != 0 && lastViewed >= newestHistoryKey();
  }

  public boolean hasUnviewed() {
    return newestHistoryKey() > fields.longValue("last_viewed", 0) && history.size() >= 2;
  }

  /** True when no unit of the check interval is set at all. */
  public boolean hasEmptyCheckTime() {
    Map<String, Object> interval = fields.map("time_between_check");
    for (Object value : interval.values()) {
      if (value != null && Fields.truthy(value)) {
        return false;
      }
    }
    return true;
  }

  /** The check interval in seconds: the units summed, with an unset unit contributing none. */
  public long thresholdSeconds() {
    return thresholdSeconds(fields.map("time_between_check"));
  }

  public static long thresholdSeconds(Map<String, Object> interval) {
    long seconds = 0;
    for (Map.Entry<String, Long> unit : INTERVAL_UNITS.entrySet()) {
      Object value = interval.get(unit.getKey());
      if (value != null && Fields.truthy(value)) {
        double amount;
        if (value instanceof Number n) {
          amount = n.doubleValue();
        } else {
          try {
            amount = Double.parseDouble(String.valueOf(value).strip());
          } catch (NumberFormatException e) {
            continue;
          }
        }
        seconds += (long) (amount * unit.getValue());
      }
    }
    return seconds;
  }

  /** True when a rule is set that makes the check depend on the previous snapshot's text. */
  public boolean hasSpecialDiffFilterOptionsSet() {
    return !fields.bool("filter_text_added", true)
        || !fields.bool("filter_text_removed", true)
        || !fields.bool("filter_text_replaced", true);
  }

  /**
   * Whether every line offered is one this watch has seen before.
   *
   * <p>The comparison is against every snapshot ever stored, not against the previous one:
   * a page that alternates between two states has nothing new in either, and this is what
   * lets a watch say so.
   */
  public boolean linesContainSomethingUniqueComparedToHistory(
      List<String> lines, boolean ignoreWhitespace, java.util.function.LongFunction<String> snapshot) {
    java.util.Set<String> local = new java.util.HashSet<>();
    for (String line : lines) {
      local.add(normaliseLine(line, ignoreWhitespace));
    }
    java.util.Set<String> existing = new java.util.HashSet<>();
    for (long timestamp : history) {
      String content = snapshot.apply(timestamp);
      if (content == null) {
        continue;
      }
      for (String line : PythonText.splitLines(content)) {
        existing.add(normaliseLine(line, ignoreWhitespace));
      }
    }
    return !existing.containsAll(local);
  }

  private static String normaliseLine(String line, boolean ignoreWhitespace) {
    return ignoreWhitespace
        ? PythonText.translateWhitespaceAway(line).toLowerCase(Locale.ROOT)
        : PythonText.strip(line).toLowerCase(Locale.ROOT);
  }

  /**
   * The snapshot the difference view opens on, given what the operator last looked at.
   *
   * <p>Not simply the previous one: an operator who has not looked since three changes ago
   * should see all three, so the answer is the newest snapshot they had already seen.
   */
  public Long fromVersionBasedOnLastViewed() {
    if (history.isEmpty()) {
      return null;
    }
    if (history.size() == 1) {
      return history.get(0);
    }
    long lastViewed = fields.longValue("last_viewed", 0);
    List<Long> sorted = new ArrayList<>(history);
    sorted.sort(java.util.Comparator.reverseOrder());
    if (lastViewed >= sorted.get(0)) {
      return sorted.get(1);
    }
    for (int i = 0; i + 1 < sorted.size(); i++) {
      long newer = sorted.get(i);
      long older = sorted.get(i + 1);
      if (lastViewed < newer && lastViewed >= older) {
        return older;
      }
    }
    return sorted.get(sorted.size() - 1);
  }

  public void pause() {
    fields.put("paused", true);
  }

  public void unpause() {
    fields.put("paused", false);
  }

  public void togglePause() {
    fields.put("paused", !fields.bool("paused"));
  }

  public void mute() {
    fields.put("notification_muted", true);
  }

  public void unmute() {
    fields.put("notification_muted", false);
  }

  public void toggleMute() {
    fields.put("notification_muted", !fields.bool("notification_muted"));
  }

  /** Everything a check leaves behind, cleared. */
  public void clearWatch() {
    Map<String, Object> cleared = new LinkedHashMap<>();
    cleared.put("last_checked", 0);
    cleared.put("last_error", false);
    cleared.put("last_notification_error", null);
    cleared.put("last_viewed", 0);
    cleared.put("previous_md5", false);
    cleared.put("check_count", 0);
    cleared.put("fetch_time", 0.0);
    cleared.put("has_ldjson_price_data", null);
    cleared.put("track_ldjson_price_data", null);
    cleared.put("consecutive_filter_failures", 0);
    cleared.put("page_title", null);
    cleared.put("remote_server_reply", null);
    cleared.put("content-type", null);
    fields.putAll(cleared);
    fields.remove("last_filter_config_hash");
    fields.remove("restock");
    history.clear();
  }

  @Override
  public Object attribute(String name) {
    if (fields.has(name)) {
      return fields.get(name);
    }
    return switch (name) {
      case "uuid" -> uuid();
      case "history_n" -> (long) history.size();
      case "last_changed" -> lastChanged();
      case "newest_history_key" -> newestHistoryKey();
      case "viewed" -> viewed();
      case "has_unviewed" -> hasUnviewed();
      case "is_pdf" -> isPdf();
      case "is_source_type_url" -> isSourceTypeUrl();
      case "has_empty_checktime" -> hasEmptyCheckTime();
      case "threshold_seconds" -> thresholdSeconds();
      default -> PyValue.UNDEFINED;
    };
  }
}
