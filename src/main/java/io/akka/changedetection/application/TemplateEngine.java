package io.akka.changedetection.application;

import io.akka.changedetection.diff.DiffRenderer;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.jinja.Filters;
import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.text.PyRegex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The template environments the rebuild renders with.
 *
 * <p>Two of them, and the difference matters. The one the interface uses can load the shipped
 * templates and knows about the interface's own filters; the one a notification body uses can
 * load nothing at all and knows about none of them. A notification body is written by whoever
 * configured the watch, so letting it reach a template file would let it read one.
 */
public final class TemplateEngine {

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]");

  /** The marks a shown difference carries, which are turned back into markup and nothing else. */
  private static final Pattern OUTER_DIFF_SPAN =
      Pattern.compile(
          "&lt;span style=&#34;("
              + Pattern.quote(DiffRenderer.REMOVED_STYLE)
              + "|"
              + Pattern.quote(DiffRenderer.ADDED_STYLE)
              + ")&#34; role=&#34;(deletion|insertion|note)&#34;"
              + " aria-label=&#34;([^&]+?)&#34; title=&#34;([^&]+?)&#34;&gt;",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern INNER_DIFF_SPAN =
      Pattern.compile(
          "&lt;span style=&#34;("
              + Pattern.quote(DiffRenderer.REMOVED_INNER_STYLE)
              + "|"
              + Pattern.quote(DiffRenderer.ADDED_INNER_STYLE)
              + ")&#34;&gt;",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern SAFE_COLOUR =
      Pattern.compile(
          "^(#[0-9a-fA-F]{3,8}|rgba?\\([0-9,.%\\s]+\\)|hsla?\\([0-9,.%\\s]+\\)|[a-zA-Z]+)$");

  private TemplateEngine() {}

  static int countOf(String haystack, String needle) {
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
  }

  /** The environment a notification body renders in: no loader, no interface filters. */
  public static Environment notifications() {
    Environment environment = new Environment();
    environment.setAutoescape(false);
    installCommonFilters(environment, null);
    return environment;
  }

  /** The environment the interface renders in, able to load the shipped templates. */
  public static Environment interfaceTemplates(Map<String, Object> application) {
    Environment environment =
        new Environment(
            name -> {
              String source = load(name);
              if (source == null) {
                throw new io.akka.changedetection.jinja.JinjaException(
                    "no template named '" + name + "'");
              }
              return source;
            });
    // Every value written into a page is escaped unless the template says otherwise, because
    // most of what a page shows -- a page's own title, its error message, a tag's name -- came
    // from somewhere the operator does not control.
    environment.setAutoescape(true);
    installCommonFilters(environment, application);
    installInterfaceFilters(environment, application);
    return environment;
  }

  static String load(String name) {
    String path = "/changedetection/templates/" + name;
    try (InputStream stream = TemplateEngine.class.getResourceAsStream(path)) {
      if (stream == null) {
        return null;
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  private static void installCommonFilters(Environment environment, Map<String, Object> application) {
    environment.putFilter(
        "regex_replace",
        (env, value, positional, keyword) ->
            Filters.regexReplace(
                PyValue.asString(value),
                PyValue.asString(positional.get(0)),
                positional.size() > 1 ? PyValue.asString(positional.get(1)) : "",
                positional.size() > 2 ? (int) Filters.toLong(positional.get(2), 0L) : 0));

    environment.putFilter(
        "regex_search",
        (env, value, positional, keyword) -> {
          try {
            return PyRegex.compile(PyValue.asString(positional.get(0)))
                .matcher(PyValue.asString(value))
                .find();
          } catch (RuntimeException e) {
            return false;
          }
        });
  }

  private static void installInterfaceFilters(
      Environment environment, Map<String, Object> application) {

    environment.putFilter(
        "format_number_locale",
        (env, value, positional, keyword) ->
            String.format(Locale.US, "%,.2f", PyValue.toDouble(value)));

    environment.putFilter(
        "format_int_locale",
        (env, value, positional, keyword) ->
            String.format(Locale.US, "%,d", Filters.toLong(value, 0L)));

    environment.putFilter(
        "format_seconds_ago",
        (env, value, positional, keyword) -> {
          if (Boolean.FALSE.equals(value) || value == null) {
            return "Not yet";
          }
          long seconds = System.currentTimeMillis() / 1000 - Filters.toLong(value, 0L);
          return String.format(Locale.US, "%,d", seconds);
        });

    environment.putFilter(
        "format_timestamp_timeago",
        (env, value, positional, keyword) -> {
          long timestamp = Filters.toLong(value, 0L);
          if (timestamp == 0) {
            return "Not yet";
          }
          return timeAgo(timestamp, shortForm(application));
        });

    environment.putFilter(
        "format_last_checked_time",
        (env, value, positional, keyword) -> {
          Object lastChecked = PyValue.getAttribute(value, "last_checked");
          long timestamp = Filters.toLong(lastChecked, 0L);
          if (timestamp == 0) {
            return "Not yet";
          }
          return timeAgo(timestamp, shortForm(application));
        });

    environment.putFilter(
        "format_duration",
        (env, value, positional, keyword) -> formatDuration(Filters.toLong(value, 0L)));

    environment.putFilter(
        "pagination_slice",
        (env, value, positional, keyword) -> {
          // The interface passes the offset by name; a filter that only read a positional
          // argument would fail on every list page rather than on none.
          Object requested = keyword.containsKey("skip")
              ? keyword.get("skip")
              : (positional.isEmpty() ? null : positional.get(0));
          int skip = (int) Filters.toLong(requested, 0L);
          int perPage =
              application == null
                  ? 50
                  : (int) Filters.toLong(application.getOrDefault("pager_size", 50), 50L);
          List<Object> items = PyValue.iterate(value);
          if (perPage <= 0) {
            return items;
          }
          int from = Math.min(skip, items.size());
          int to = Math.min(skip + perPage, items.size());
          return new ArrayList<>(items.subList(from, to));
        });

    environment.putFilter(
        "sanitize_tag_class",
        (env, value, positional, keyword) -> {
          String sanitised =
              NON_ALPHANUMERIC
                  .matcher(PyValue.asString(value))
                  .replaceAll("")
                  .toLowerCase(Locale.ROOT);
          if (!sanitised.isEmpty() && !Character.isLetter(sanitised.charAt(0))) {
            sanitised = "tag" + sanitised;
          }
          return sanitised.isEmpty() ? "tag" : sanitised;
        });

    environment.putFilter(
        "safe_css_colour",
        (env, value, positional, keyword) -> {
          // A colour goes straight into a style attribute, so anything that is not a colour is
          // dropped rather than escaped: escaping would leave a broken rule on the page.
          String colour = PyValue.asString(value).strip();
          return SAFE_COLOUR.matcher(colour).matches() ? colour : "";
        });

    environment.putFilter(
        "fetcher_status_icons",
        (env, value, positional, keyword) -> {
          String fetcher = PyValue.asString(value);
          String icon =
              switch (fetcher) {
                case "html_webdriver", "html_webdriver_selenium" -> "google-chrome-icon.png";
                default -> null;
              };
          if (icon == null) {
            return "";
          }
          return new PyValue.Markup(
              "<img class=\"status-icon\" src=\"/static/images/" + icon
                  + "\" alt=\"Using a Chrome browser\" title=\"Using a Chrome browser\">");
        });

    environment.putFilter(
        "diff_unescape_difference_spans",
        (env, value, positional, keyword) -> {
          // The difference arrives as the watched page's own text, so it is escaped whole and
          // only the marks this rebuild put in it are turned back into markup -- and only as
          // many closing marks as there were opening ones, so a page containing the literal
          // text of a closing tag cannot close a span it never opened.
          String text = PyValue.asString(value);
          if (text.isEmpty()) {
            return new PyValue.Markup("");
          }
          String escaped = Filters.escapeHtml(text);
          String outer =
              OUTER_DIFF_SPAN.matcher(escaped).replaceAll("<span style=\"$1\" role=\"$2\" aria-label=\"$3\" title=\"$4\">");
          String result = INNER_DIFF_SPAN.matcher(outer).replaceAll("<span style=\"$1\">");
          int opened = countOf(result, "<span style=");
          int closed = countOf(escaped, "&lt;/span&gt;");
          for (int index = 0; index < Math.min(opened, closed); index++) {
            result = result.replaceFirst(java.util.regex.Pattern.quote("&lt;/span&gt;"), "</span>");
          }
          return new PyValue.Markup(result);
        });

    environment.putGlobal(
        "is_checking_now",
        (PyValue.Callable)
            (positional, keyword) -> {
              Object watch = positional.isEmpty() ? null : positional.get(0);
              return PyValue.truthy(PyValue.getAttribute(watch, "__checking"));
            });
  }

  private static boolean shortForm(Map<String, Object> application) {
    if (application == null) {
      return false;
    }
    Object ui = application.get("ui");
    if (ui instanceof Map<?, ?> map) {
      return "short".equals(String.valueOf(map.get("timeago_format")));
    }
    return false;
  }

  /**
   * How long ago something happened, in words.
   *
   * <p>The wording is what the interface shows on every row of its main list, so the
   * thresholds and the plural forms are part of what a person reads rather than a detail.
   */
  static String timeAgo(long timestamp, boolean shortForm) {
    long seconds = Math.max(0, System.currentTimeMillis() / 1000 - timestamp);
    if (seconds < 45) {
      return shortForm ? "just now" : "just now";
    }
    long minutes = Math.round(seconds / 60.0);
    if (seconds < 90) {
      return shortForm ? "1m ago" : "1 minute ago";
    }
    if (minutes < 45) {
      return shortForm ? minutes + "m ago" : minutes + " minutes ago";
    }
    long hours = Math.round(minutes / 60.0);
    if (minutes < 90) {
      return shortForm ? "1h ago" : "1 hour ago";
    }
    if (hours < 24) {
      return shortForm ? hours + "h ago" : hours + " hours ago";
    }
    long days = Math.round(hours / 24.0);
    if (hours < 42) {
      return shortForm ? "1d ago" : "1 day ago";
    }
    if (days < 30) {
      return shortForm ? days + "d ago" : days + " days ago";
    }
    long months = Math.round(days / 30.0);
    if (days < 45) {
      return shortForm ? "1mo ago" : "1 month ago";
    }
    if (months < 12) {
      return shortForm ? months + "mo ago" : months + " months ago";
    }
    long years = Math.round(months / 12.0);
    if (months < 18) {
      return shortForm ? "1yr ago" : "1 year ago";
    }
    return shortForm ? years + "yrs ago" : years + " years ago";
  }

  /** A span of time in words, largest unit first, omitting the units that are zero. */
  static String formatDuration(long seconds) {
    if (seconds <= 0) {
      return "0 seconds";
    }
    long days = seconds / 86400;
    long remainderSeconds = seconds % 86400;
    long years = days / 365;
    long remainingDays = days % 365;
    long months = remainingDays / 30;
    remainingDays = remainingDays % 30;
    long weeks = remainingDays / 7;
    long leftoverDays = remainingDays % 7;
    long hours = remainderSeconds / 3600;
    long minutes = (remainderSeconds % 3600) / 60;
    long secs = remainderSeconds % 60;

    List<String> parts = new ArrayList<>();
    if (years > 0) {
      parts.add(years + " " + (years == 1 ? "year" : "years"));
    }
    if (months > 0) {
      parts.add(months + " " + (months == 1 ? "month" : "months"));
    }
    if (weeks > 0) {
      parts.add(weeks + " " + (weeks == 1 ? "week" : "weeks"));
    }
    if (leftoverDays > 0) {
      parts.add(leftoverDays + " " + (leftoverDays == 1 ? "day" : "days"));
    }
    if (hours > 0) {
      parts.add(hours + " " + (hours == 1 ? "hour" : "hours"));
    }
    if (minutes > 0) {
      parts.add(minutes + " " + (minutes == 1 ? "minute" : "minutes"));
    }
    if (secs > 0 || parts.isEmpty()) {
      parts.add(secs + " " + (secs == 1 ? "second" : "seconds"));
    }
    return String.join(", ", parts);
  }
}
