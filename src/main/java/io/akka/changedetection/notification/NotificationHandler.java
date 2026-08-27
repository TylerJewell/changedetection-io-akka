package io.akka.changedetection.notification;

import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.jinja.Filters;
import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.LlmSettings;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One notification, rendered and delivered.
 *
 * <p>The body is a template written by whoever configured the watch, rendered against tokens
 * that include page content the watch fetched. That combination is why the escaping below is
 * not optional: the page's text has already had its markup entities decoded by the time it
 * becomes a token, so a page that visibly displays a link would otherwise put a live link into
 * the operator's mailbox.
 *
 * <p>An address is rendered as a template too, so a notification can be routed by what changed.
 */
public final class NotificationHandler {

  private static final Pattern NEWLINES = Pattern.compile("\\r\\n|\\r|\\n");
  private static final Pattern LINK =
      Pattern.compile("\\bhttps?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

  private final List<Sender> senders = new ArrayList<>();
  private final Environment templates;

  public NotificationHandler(Environment templates) {
    this.templates = templates;
    senders.add(new MailSender());
    senders.add(new WebhookSender());
    senders.add(new DiscardingSender());
  }

  public void addSender(Sender sender) {
    senders.add(0, sender);
  }

  /** What a rendered notification looked like, whether or not it was sent. */
  public record Rendered(String url, String title, String body) {}

  /**
   * Renders the notification for every address on it and sends each one.
   *
   * @param send when false, the notification is rendered and not delivered, which is how the
   *     feed builds an entry body out of the same template the operator wrote
   */
  public List<Rendered> process(
      Map<String, Object> notification, Map<String, Object> application, boolean send) {
    List<String> urls = stringList(notification.get("notification_urls"));
    if (urls.isEmpty()) {
      return List.of();
    }

    String requestedFormat =
        String.valueOf(
            notification.getOrDefault("notification_format", AppSettings.DEFAULT_NOTIFICATION_FORMAT));
    if (requestedFormat.equals(Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT)) {
      requestedFormat =
          String.valueOf(
              application.getOrDefault(
                  "notification_format", AppSettings.DEFAULT_NOTIFICATION_FORMAT));
    }
    String originalFormat = requestedFormat;
    boolean htmlOutput = ServiceTweaks.isHtmlFormat(originalFormat);

    Map<String, Object> parameters = new LinkedHashMap<>(NotificationContext.emptyContext());
    parameters.putAll(notification);

    String bodyTemplate = String.valueOf(parameters.getOrDefault("notification_body", ""));
    String titleTemplate = String.valueOf(parameters.getOrDefault("notification_title", ""));
    String scanText = bodyTemplate + titleTemplate;

    parameters.putAll(
        NotificationContext.renderDiffTokens(
            scanText,
            asString(parameters.get("prev_snapshot")),
            asString(parameters.get("current_snapshot")),
            !originalFormat.equals("text"),
            htmlOutput));
    parameters.put("raw_diff", parameters.getOrDefault("diff", ""));

    // A plain-language summary stands in for the raw difference when the operator asked for
    // that, so a notification reads as a sentence rather than as a patch.
    String changeSummary =
        String.valueOf(parameters.getOrDefault("_llm_change_summary", "")).strip();
    if (changeSummary.equals("null")) {
      changeSummary = "";
    }
    if (!changeSummary.isEmpty()
        && Fields.truthy(LlmSettings.of(application).get("override_diff_with_summary"))) {
      parameters.put("diff", changeSummary);
    }
    // The two AI tokens are filled only when the operator's own template mentions them; the
    // evaluation they come from may not have run at all.
    if (scanText.contains("llm_summary")
        || scanText.contains("llm_intent")
        || scanText.contains("raw_diff")) {
      String fromEvaluation = "";
      if (parameters.get("_llm_result") instanceof Map<?, ?> result) {
        Object summary = result.get("summary");
        fromEvaluation = summary == null ? "" : String.valueOf(summary);
      }
      parameters.put("llm_summary", changeSummary.isEmpty() ? fromEvaluation : changeSummary);
      parameters.put("llm_intent", parameters.getOrDefault("_llm_intent", ""));
    }

    if (htmlOutput) {
      // Everything that came from the watched page is escaped before the template renders it.
      // The operator's own markup in the template is outside these values and is untouched, and
      // the difference markers carry no markup characters so they survive to be replaced later.
      for (String key : new ArrayList<>(parameters.keySet())) {
        boolean pageContent =
            key.startsWith("diff")
                || key.equals("raw_diff")
                || key.equals("current_snapshot")
                || key.equals("prev_snapshot")
                || key.equals("triggered_text");
        if (!pageContent) {
          continue;
        }
        Object value = parameters.get(key);
        if (value == null
            || value instanceof NotificationContext.FormattableDiff
            || value instanceof NotificationContext.FormattableExtract) {
          continue;
        }
        parameters.put(key, Filters.escapeHtml(String.valueOf(value)));
      }
    }

    List<Rendered> rendered = new ArrayList<>();
    for (String rawUrl : urls) {
      String url = rawUrl == null ? "" : rawUrl.strip();
      if (url.isEmpty() || url.startsWith("#")) {
        continue;
      }

      String body = templates.renderString(bodyTemplate, parameters);
      String title = templates.renderString(titleTemplate, parameters);
      if (Fields.truthy(parameters.get("markup_text_links_to_html_links"))) {
        body = linkify(body);
      }
      url = templates.renderString(url, parameters);

      if (htmlOutput) {
        // A run of two spaces is alignment the difference produced, and markup would collapse
        // it; a single space is ordinary text and is left alone so words do not run together.
        body = body.replace("  ", "&nbsp;&nbsp;");
      }

      ServiceTweaks.Result tweaked =
          ServiceTweaks.apply(url, body, title, originalFormat);

      String deliveryFormat = alignFormat(originalFormat);
      String finalUrl = tweaked.url();
      String finalBody = tweaked.body();
      if (originalFormat.equals("markdown")) {
        finalBody = finalBody.replace("---", "\n\n---\n\n");
        deliveryFormat = "html";
      }
      finalUrl = withFormat(finalUrl, deliveryFormat);

      rendered.add(new Rendered(finalUrl, tweaked.title(), finalBody));

      if (send) {
        deliver(
            new Sender.Message(
                finalUrl,
                tweaked.title(),
                finalBody,
                deliveryFormat,
                (byte[]) parameters.get("screenshot_bytes"),
                "screenshot.jpeg"));
      }
    }
    return rendered;
  }

  /** Whether an address names a destination this rebuild can deliver to. */
  public boolean canDeliverTo(String url) {
    String scheme = schemeOf(url);
    if (scheme.isEmpty()) {
      return false;
    }
    for (Sender sender : senders) {
      if (sender.schemes().contains(scheme)) {
        return true;
      }
    }
    return false;
  }

  private void deliver(Sender.Message message) {
    String scheme = schemeOf(message.url());
    for (Sender sender : senders) {
      if (sender.schemes().contains(scheme)) {
        sender.send(message);
        return;
      }
    }
    throw new NotificationFailed(
        "No way to deliver to '" + scheme + "' is built in to this rebuild");
  }

  private static String schemeOf(String url) {
    try {
      String scheme = URI.create(url).getScheme();
      return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      int at = url.indexOf("://");
      return at > 0 ? url.substring(0, at).toLowerCase(Locale.ROOT) : "";
    }
  }

  /** The format written onto the address, so the far end does not convert it again. */
  private static String withFormat(String url, String format) {
    if (url.contains("format=")) {
      return url;
    }
    return url + (url.contains("?") ? "&" : "?") + "format=" + format;
  }

  private static String alignFormat(String format) {
    if (format == null || format.isEmpty()) {
      return "text";
    }
    if (format.startsWith("html")) {
      return "html";
    }
    if (format.startsWith("markdown")) {
      return "markdown";
    }
    return "text";
  }

  /** Plain addresses in a plain-text body turned into links, for a destination that shows them. */
  static String linkify(String body) {
    Matcher matcher = LINK.matcher(body);
    StringBuilder sb = new StringBuilder();
    int last = 0;
    while (matcher.find()) {
      sb.append(Filters.escapeHtml(body.substring(last, matcher.start())));
      String url = matcher.group();
      sb.append("<a href=\"").append(Filters.escapeHtml(url)).append("\">")
          .append(Filters.escapeHtml(url)).append("</a>");
      last = matcher.end();
    }
    sb.append(Filters.escapeHtml(body.substring(last)));
    return sb.toString();
  }

  private static String asString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Object value) {
    List<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
    } else if (value instanceof String s && !s.isBlank()) {
      out.add(s);
    }
    return out;
  }

  /**
   * A destination that accepts a notification and does nothing with it.
   *
   * <p>Used where the rendering is the point and the sending is not -- the feed renders every
   * entry through the notification template, and the interface's "send a test" needs to render
   * before it sends.
   */
  static final class DiscardingSender implements Sender {
    @Override
    public List<String> schemes() {
      return List.of("null");
    }

    @Override
    public void send(Message message) {
      // Nothing to do: this destination exists so that rendering can happen without sending.
    }
  }
}
