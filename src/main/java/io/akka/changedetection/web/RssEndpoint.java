package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Notifier;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.TemplateEngine;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.notification.NotificationHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The feeds a reader subscribes to.
 *
 * <p>An entry's body is the operator's own notification template rendered against the change,
 * not a second description written here -- so what a feed reader shows and what an email says
 * are the same words, and changing one changes both.
 *
 * <p>Feed readers cannot sign in, so these are guarded by a token in the address rather than by
 * a session. Where no token is set the feeds are open, which is what the original does and what
 * a reader with no way to authenticate needs.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class RssEndpoint extends AbstractHttpEndpoint {

  /**
   * Characters a feed reader will refuse the whole document over.
   *
   * <p>The control range minus tab, newline and carriage return: a watched page that contains
   * one would otherwise make every entry in the feed unreadable, not just its own.
   */
  private static final Pattern BAD_CHARACTERS =
      Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

  private static final DateTimeFormatter PUB_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

  private final ComponentClient componentClient;

  public RssEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  // Some readers stored the address with a trailing slash, from a time when that was its
  // own route. The runtime treats the two spellings as one path, so the feed below answers
  // both rather than one redirecting to the other.

  @Get("/rss")
  public HttpResponse feed() {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireToken(store);
    if (refusal != null) {
      return refusal;
    }
    Notifier notifier = new Notifier(store, TemplateEngine.notifications());
    Map<String, Object> application = store.application();
    String format = contentFormat(application);

    String limitTag = Requests.queryValue(requestContext(), "tag", "").toLowerCase(Locale.ROOT)
        .strip();
    for (Map.Entry<String, Map<String, Object>> entry : store.tags().entrySet()) {
      if (String.valueOf(entry.getValue().getOrDefault("title", ""))
          .toLowerCase(Locale.ROOT)
          .strip()
          .equals(limitTag)) {
        limitTag = entry.getKey();
      }
    }

    List<Watch> watches = new ArrayList<>();
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      Watch watch = entry.getValue();
      if (Fields.truthy(application.get("rss_hide_muted_watches"))
          && Fields.truthy(watch.asMap().get("notification_muted"))) {
        continue;
      }
      if (!limitTag.isEmpty() && !watch.fields().strings("tags").contains(limitTag)) {
        continue;
      }
      watches.add(watch);
    }
    watches.sort(Comparator.comparingLong(Watch::lastChanged));

    StringBuilder entries = new StringBuilder();
    for (Watch watch : watches) {
      List<Long> dates = store.watch(watch.uuid()).history();
      // A watch with one snapshot has never changed, so there is nothing for a reader to read.
      if (dates.size() < 2) {
        continue;
      }
      if (watch.viewed()) {
        continue;
      }
      long to = dates.get(dates.size() - 1);
      String label = labelOf(store, watch);
      String body = renderBody(store, notifier, watch, format, -2, -1, label);
      entries.append(
          entry(
              label,
              externalBase(store) + "diff/" + watch.uuid(),
              body,
              watch.uuid() + "/" + to,
              to,
              categories(store, watch)));
    }
    return document(
        "changedetection.io", "Feed description", "https://changedetection.io", entries);
  }

  @Get("/rss/watch/{uuid}")
  public HttpResponse singleWatch(String uuid) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireToken(store);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> application = store.application();
    String format = contentFormat(application);

    String resolved = uuid;
    if ("first".equals(uuid)) {
      List<String> all = store.watchUuids();
      if (all.isEmpty()) {
        return Requests.text(StatusCodes.NOT_FOUND, "Watch with UUID " + uuid + " not found");
      }
      resolved = all.get(all.size() - 1);
    }
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      return Requests.text(StatusCodes.NOT_FOUND, "Watch with UUID " + resolved + " not found");
    }
    List<Long> dates = state.history();
    if (dates.size() < 2) {
      return Requests.text(
          StatusCodes.BAD_REQUEST,
          "Watch " + resolved
              + " does not have enough history snapshots to show changes (need at least 2)");
    }

    Watch watch = state.asWatch();
    Notifier notifier = new Notifier(store, TemplateEngine.notifications());
    int configured = intOf(application.get("rss_diff_length"), 5);
    int possible = dates.size() - 1;
    int count = configured > 0 ? Math.min(configured, possible) : possible;

    String url = watch.fields().string("url", "");
    String label = labelOf(store, watch);
    String title =
        label.equals(url)
            ? "changedetection.io - " + url
            : "changedetection.io - " + label + " (" + url + ")";

    StringBuilder entries = new StringBuilder();
    // Oldest first, because a reader shows what it was given last at the top.
    for (int index = count - 1; index >= 0; index--) {
      int toIndex = -(index + 1);
      int fromIndex = -(index + 2);
      long to = dates.get(dates.size() + toIndex);
      String body = renderBody(store, notifier, watch, format, fromIndex, toIndex, label);
      entries.append(
          entry(
              label + " - Change @ " + changeMoment(to),
              url,
              body,
              resolved + "/" + to,
              to,
              categories(store, watch)));
    }
    return document(title, "Changes", "https://changedetection.io", entries);
  }

  @Get("/rss/tag/{tagUuid}")
  public HttpResponse tagFeed(String tagUuid) {
    Store store = new Store(componentClient);
    HttpResponse refusal = requireToken(store);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> tag = store.tags().get(tagUuid);
    if (tag == null) {
      return Requests.text(StatusCodes.NOT_FOUND, "Tag with UUID " + tagUuid + " not found");
    }
    Map<String, Object> application = store.application();
    String format = contentFormat(application);
    String tagTitle = String.valueOf(tag.getOrDefault("title", "Unknown Tag"));
    Notifier notifier = new Notifier(store, TemplateEngine.notifications());

    StringBuilder entries = new StringBuilder();
    for (Map.Entry<String, Watch> row : store.allWatches().entrySet()) {
      Watch watch = row.getValue();
      if (!watch.fields().strings("tags").contains(tagUuid)) {
        continue;
      }
      if (Fields.truthy(application.get("rss_hide_muted_watches"))
          && Fields.truthy(watch.asMap().get("notification_muted"))) {
        continue;
      }
      List<Long> dates = store.watch(row.getKey()).history();
      if (dates.size() < 2 || watch.viewed()) {
        continue;
      }
      long to = dates.get(dates.size() - 1);
      String label = labelOf(store, watch);
      String body = renderBody(store, notifier, watch, format, -2, -1, label);
      entries.append(
          entry(
              label + " - Change @ " + changeMoment(to),
              externalBase(store) + "diff/" + row.getKey(),
              body,
              row.getKey() + "/" + to,
              to,
              categories(store, watch)));
    }
    return document(
        "changedetection.io - " + tagTitle,
        "Changes for watches tagged with " + tagTitle,
        "https://changedetection.io",
        entries);
  }

  // ------------------------------------------------------------------ pieces

  private HttpResponse requireToken(Store store) {
    String presented = Requests.queryValue(requestContext(), "token", "");
    Object stored = store.application().get("rss_access_token");
    if (stored == null || String.valueOf(stored).isEmpty()) {
      return null;
    }
    if (String.valueOf(stored).equals(presented)) {
      return null;
    }
    return Requests.text(StatusCodes.FORBIDDEN, "Access denied, bad token");
  }

  private String renderBody(
      Store store,
      Notifier notifier,
      Watch watch,
      String format,
      int fromIndex,
      int toIndex,
      String label) {
    Map<String, Object> notification = new LinkedHashMap<>();
    notification.put(
        "notification_urls", List.of("null://just-sending-a-null-test-for-the-render-in-RSS"));
    notification.put("notification_body", template(store, notifier, watch, format));
    notification.put("notification_format", format);
    notification.put("watch_label", label);
    List<NotificationHandler.Rendered> rendered =
        notifier.render(watch, notification, fromIndex, toIndex);
    return rendered.isEmpty() ? "" : rendered.get(0).body();
  }

  /**
   * The template one entry is written from.
   *
   * <p>Three settings decide it, in order: the operator may point the feed at the notification
   * body each watch already has, may give the feed a body of its own, or may leave it to the
   * built-in one -- of which there are two, because a plain-text feed and a markup feed cannot
   * share a template.
   */
  static String template(Store store, Notifier notifier, Watch watch, String format) {
    Map<String, Object> application = store.application();
    if ("notification_body".equals(String.valueOf(application.get("rss_template_type")))) {
      Object cascaded = notifier.cascadingValue(watch, "notification_body");
      return cascaded == null ? "" : String.valueOf(cascaded);
    }
    Object override = application.get("rss_template_override");
    if (override != null && !String.valueOf(override).strip().isEmpty()) {
      return String.valueOf(override);
    }
    return format.contains("text")
        ? AppSettings.RSS_TEMPLATE_PLAINTEXT_DEFAULT
        : AppSettings.RSS_TEMPLATE_HTML_DEFAULT;
  }

  static String contentFormat(Map<String, Object> application) {
    Object configured = application.get("rss_content_format");
    return configured == null || String.valueOf(configured).isEmpty()
        ? AppSettings.RSS_CONTENT_FORMAT_DEFAULT
        : String.valueOf(configured);
  }

  static String labelOf(Store store, Watch watch) {
    if (WatchListFilters.usePageTitle(store.application())
        || Fields.truthy(watch.asMap().get("use_page_title_in_list"))) {
      return Render.labelOf(watch, TemplateEngine.notifications());
    }
    return watch.fields().string("url", "");
  }

  private static List<String> categories(Store store, Watch watch) {
    List<String> out = new ArrayList<>();
    for (String uuid : watch.fields().strings("tags")) {
      Map<String, Object> tag = store.tags().get(uuid);
      if (tag != null && !String.valueOf(tag.getOrDefault("title", "")).isEmpty()) {
        out.add(String.valueOf(tag.get("title")));
      }
    }
    return out;
  }

  private String externalBase(Store store) {
    Object configured = store.application().get("active_base_url");
    String base =
        configured == null || String.valueOf(configured).isBlank()
            ? String.valueOf(store.application().getOrDefault("base_url", ""))
            : String.valueOf(configured);
    if (base == null || base.isBlank() || "null".equals(base)) {
      base = "";
    }
    return base.endsWith("/") ? base : base + "/";
  }

  private static String changeMoment(long epochSeconds) {
    return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  private static int intOf(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).strip());
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String entry(
      String title,
      String link,
      String content,
      String guid,
      long timestamp,
      List<String> categories) {
    StringBuilder out = new StringBuilder();
    out.append("    <item>\n");
    out.append("      <title>").append(escape(title)).append("</title>\n");
    if (link != null && !link.isEmpty()) {
      out.append("      <link>").append(escape(link)).append("</link>\n");
    }
    out.append("      <description><![CDATA[").append(cdataSafe(clean(content)))
        .append("]]></description>\n");
    out.append("      <guid isPermaLink=\"false\">").append(escape(guid)).append("</guid>\n");
    out.append("      <pubDate>")
        .append(
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC)
                .format(PUB_DATE))
        .append("</pubDate>\n");
    for (String category : categories) {
      out.append("      <category>").append(escape(category)).append("</category>\n");
    }
    out.append("    </item>\n");
    return out.toString();
  }

  private static HttpResponse document(
      String title, String description, String link, StringBuilder entries) {
    StringBuilder out = new StringBuilder();
    out.append("<?xml version='1.0' encoding='UTF-8'?>\n");
    out.append("<rss xmlns:atom=\"http://www.w3.org/2005/Atom\" version=\"2.0\">\n");
    out.append("  <channel>\n");
    out.append("    <title>").append(escape(title)).append("</title>\n");
    out.append("    <link>").append(escape(link)).append("</link>\n");
    out.append("    <description>").append(escape(description)).append("</description>\n");
    out.append("    <docs>http://www.rssboard.org/rss-specification</docs>\n");
    out.append("    <generator>changedetection.io</generator>\n");
    out.append(entries);
    out.append("  </channel>\n");
    out.append("</rss>\n");
    return Requests.bytes(
        StatusCodes.OK,
        akka.http.javadsl.model.ContentTypes.parse("application/rss+xml; charset=utf-8"),
        out.toString().getBytes(StandardCharsets.UTF_8));
  }

  static String clean(String content) {
    if (content == null) {
      return "";
    }
    Matcher matcher = BAD_CHARACTERS.matcher(content);
    return matcher.find() ? matcher.replaceAll("") : content;
  }

  /** A closing marker inside the content would end the section early, so it is broken up. */
  private static String cdataSafe(String content) {
    return content.replace("]]>", "]]]]><![CDATA[>");
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
