package io.akka.changedetection.notification;

import io.akka.changedetection.diff.DiffRenderer;
import io.akka.changedetection.jinja.Filters;
import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.text.PythonText;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a notification body may refer to.
 *
 * <p>This is also the list of valid tokens: a body that names something not here is rejected
 * when it is saved, so a typo is caught at the point of writing rather than silently sending
 * an empty notification months later.
 *
 * <p>Several of the tokens are callable as well as printable -- {@code {{ diff }}} is the whole
 * difference and {@code {{ diff(lines=5) }}} is the first five lines of it. That is not
 * cosmetic: a service with a small message limit needs the second form, and without it the
 * notification is silently truncated at the far end.
 */
public final class NotificationContext {

  /** A difference that prints one way and can be asked for another. */
  public static final class FormattableDiff implements PyValue.Callable, CharSequence {
    private final String previous;
    private final String current;
    private final DiffRenderer.Options base;
    private final boolean escapeOutput;
    private final String rendered;

    public FormattableDiff(
        String previous, String current, DiffRenderer.Options base, boolean escapeOutput) {
      this.previous = previous == null ? "" : previous;
      this.current = current == null ? "" : current;
      this.base = base;
      this.escapeOutput = escapeOutput;
      String value =
          this.previous.isEmpty() && this.current.isEmpty()
              ? ""
              : DiffRenderer.render(this.previous, this.current, base);
      this.rendered = escapeOutput && !value.isEmpty() ? Filters.escapeHtml(value) : value;
    }

    @Override
    public Object call(List<Object> positional, Map<String, Object> keyword) {
      DiffRenderer.Options options = base.copy();
      if (truthy(keyword.get("added_only"))) {
        options.includeRemoved = false;
      }
      if (truthy(keyword.get("removed_only"))) {
        options.includeAdded = false;
      }
      if (keyword.containsKey("context")) {
        options.contextLines = (int) Filters.toLong(keyword.get("context"), 0L);
      }
      if (keyword.containsKey("word_diff")) {
        options.wordDiff = truthy(keyword.get("word_diff"));
      }
      if (truthy(keyword.get("case_insensitive"))) {
        options.caseInsensitive = true;
      }
      if (truthy(keyword.get("ignore_junk"))) {
        options.ignoreJunk = true;
      }
      String result = DiffRenderer.render(previous, current, options);
      if (keyword.containsKey("lines")) {
        int lines = (int) Filters.toLong(keyword.get("lines"), 0L);
        List<String> split = PythonText.splitLines(result);
        result = String.join("\n", split.subList(0, Math.min(lines, split.size())));
      }
      return escapeOutput && !result.isEmpty() ? Filters.escapeHtml(result) : result;
    }

    private static boolean truthy(Object value) {
      return value != null && PyValue.truthy(value);
    }

    @Override
    public int length() {
      return rendered.length();
    }

    @Override
    public char charAt(int index) {
      return rendered.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return rendered.subSequence(start, end);
    }

    @Override
    public String toString() {
      return rendered;
    }
  }

  /** Only the changed fragments, for the tokens that show an old or a new value alone. */
  public static final class FormattableExtract implements CharSequence {
    private final String extracted;

    public FormattableExtract(
        String previous, String current, boolean removedSide, boolean escapeOutput) {
      String value = "";
      if ((previous != null && !previous.isEmpty()) || (current != null && !current.isEmpty())) {
        DiffRenderer.Options options = new DiffRenderer.Options();
        options.wordDiff = true;
        String raw =
            DiffRenderer.render(previous == null ? "" : previous, current == null ? "" : current,
                options);
        value = removedSide
            ? DiffRenderer.extractChangedFrom(raw)
            : DiffRenderer.extractChangedTo(raw);
      }
      this.extracted = escapeOutput && !value.isEmpty() ? Filters.escapeHtml(value) : value;
    }

    @Override
    public int length() {
      return extracted.length();
    }

    @Override
    public char charAt(int index) {
      return extracted.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return extracted.subSequence(start, end);
    }

    @Override
    public String toString() {
      return extracted;
    }
  }

  /** A moment that prints one way and can be asked for another format. */
  public static final class FormattableTimestamp implements PyValue.Callable, CharSequence {
    private static final String DEFAULT_FORMAT = "%Y-%m-%d %H:%M:%S %Z";
    private final ZonedDateTime moment;
    private final String rendered;

    public FormattableTimestamp(long epochSeconds) {
      this.moment = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
      this.rendered = io.akka.changedetection.jinja.Interpreter.strftime(moment, DEFAULT_FORMAT);
    }

    @Override
    public Object call(List<Object> positional, Map<String, Object> keyword) {
      String format =
          keyword.containsKey("format")
              ? PyValue.asString(keyword.get("format"))
              : (positional.isEmpty() ? DEFAULT_FORMAT : PyValue.asString(positional.get(0)));
      return io.akka.changedetection.jinja.Interpreter.strftime(moment, format);
    }

    @Override
    public int length() {
      return rendered.length();
    }

    @Override
    public char charAt(int index) {
      return rendered.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return rendered.subSequence(start, end);
    }

    @Override
    public String toString() {
      return rendered;
    }
  }

  private NotificationContext() {}

  /** Every token, with the value it has before anything is filled in. */
  public static Map<String, Object> emptyContext() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("base_url", null);
    context.put("change_datetime", new FormattableTimestamp(Instant.now().getEpochSecond()));
    context.put("current_snapshot", null);
    for (String name : diffTokenNames()) {
      context.put(name, "");
    }
    context.put("diff_url", null);
    context.put("raw_diff", "");
    context.put("markup_text_links_to_html_links", false);
    context.put("restock", new LinkedHashMap<String, Object>());
    context.put("notification_timestamp", Instant.now().getEpochSecond());
    context.put("prev_snapshot", null);
    context.put("preview_url", null);
    context.put("screenshot", null);
    context.put("timestamp_from", null);
    context.put("timestamp_to", null);
    context.put("triggered_text", null);
    context.put("llm_summary", null);
    context.put("llm_intent", null);
    context.put("uuid", "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX");
    context.put("watch_mime_type", null);
    context.put("watch_tag", null);
    context.put("watch_title", null);
    context.put("watch_url", "https://WATCH-PLACE-HOLDER/");
    context.put("watch_uuid", "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX");
    context.put("edit_url", null);
    context.put("watch_label", null);
    return context;
  }

  /** The tokens that render a difference, each with its own settings. */
  public static List<String> diffTokenNames() {
    return List.of(
        "diff", "diff_clean", "diff_added", "diff_added_clean", "diff_full", "diff_full_clean",
        "diff_patch", "diff_removed", "diff_removed_clean", "diff_changed_from", "diff_changed_to");
  }

  /**
   * Only the difference tokens a body actually mentions are rendered.
   *
   * <p>Rendering all eleven for every notification means comparing two snapshots eleven times,
   * and a snapshot is routinely hundreds of kilobytes.
   */
  public static Map<String, Object> renderDiffTokens(
      String scanText, String previousSnapshot, String currentSnapshot, boolean wordDiff,
      boolean escapeOutput) {
    Map<String, Object> out = new LinkedHashMap<>();
    String haystack = scanText == null ? "" : scanText.toLowerCase(Locale.ROOT);
    for (String name : diffTokenNames()) {
      if (!mentions(haystack, name)) {
        continue;
      }
      if (name.equals("diff_changed_from")) {
        out.put(name, new FormattableExtract(previousSnapshot, currentSnapshot, true, escapeOutput));
        continue;
      }
      if (name.equals("diff_changed_to")) {
        out.put(name, new FormattableExtract(previousSnapshot, currentSnapshot, false, escapeOutput));
        continue;
      }
      DiffRenderer.Options options = new DiffRenderer.Options();
      options.wordDiff = wordDiff;
      switch (name) {
        case "diff_clean" -> options.includeChangeTypePrefix = false;
        case "diff_added" -> options.includeRemoved = false;
        case "diff_added_clean" -> {
          options.includeRemoved = false;
          options.includeChangeTypePrefix = false;
        }
        case "diff_full" -> options.includeEqual = true;
        case "diff_full_clean" -> {
          options.includeEqual = true;
          options.includeChangeTypePrefix = false;
        }
        case "diff_patch" -> options.patchFormat = true;
        case "diff_removed" -> options.includeAdded = false;
        case "diff_removed_clean" -> {
          options.includeAdded = false;
          options.includeChangeTypePrefix = false;
        }
        default -> {
          // The plain token keeps the defaults.
        }
      }
      out.put(
          name,
          new FormattableDiff(previousSnapshot, currentSnapshot, options, escapeOutput));
    }
    return out;
  }

  /** Whether a body mentions a token, matched whole so "diff" does not match "diff_added". */
  private static boolean mentions(String haystack, String token) {
    int at = 0;
    while ((at = haystack.indexOf(token, at)) >= 0) {
      boolean beforeOk = at == 0 || !isNameCharacter(haystack.charAt(at - 1));
      int after = at + token.length();
      boolean afterOk = after >= haystack.length() || !isNameCharacter(haystack.charAt(after));
      if (beforeOk && afterOk) {
        return true;
      }
      at = after;
    }
    return false;
  }

  private static boolean isNameCharacter(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /** Whether a format is one the system knows. */
  public static boolean isValidFormat(String format) {
    return format != null && AppSettings.NOTIFICATION_FORMATS.containsKey(format);
  }

  static DateTimeFormatter unusedFormatter() {
    return DateTimeFormatter.ISO_INSTANT;
  }
}
