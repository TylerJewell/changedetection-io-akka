package io.akka.changedetection.notification;

import io.akka.changedetection.diff.DiffRenderer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a rendered difference is dressed for the service it is going to.
 *
 * <p>The difference leaves the renderer marked up with placemarkers rather than with any
 * particular markup, precisely so this decision can be made per destination: one service
 * renders styled markup, one renders a small subset of it, one renders its own lighter markup,
 * and one renders none at all. Sending the wrong one is not a formatting nit -- it is a
 * notification full of visible tag soup, or a notification that silently exceeds a length limit
 * and never arrives.
 */
public final class ServiceTweaks {

  private static final Pattern NEWLINES = Pattern.compile("\\r\\n|\\r|\\n");

  /** What the caller ends up sending. */
  public record Result(String url, String body, String title) {}

  private ServiceTweaks() {}

  public static Result apply(String url, String body, String title, String requestedFormat) {
    if (body == null || body.strip().isEmpty()) {
      return new Result(url, body, title);
    }

    String adjustedUrl = lowercaseScheme(url);

    // A title is plain text at every destination -- an electronic mail subject, a chat message
    // heading -- so its markers are always replaced with the plainest form.
    String adjustedTitle = replacePlacemarkers(title, adjustedUrl, "text");
    String adjustedBody = body;

    if (adjustedUrl.startsWith("tgram://")) {
      adjustedBody = adjustedBody.replace("<br>", "\n").replace("</br>", "\n");
      adjustedBody = NEWLINES.matcher(adjustedBody).replaceAll("\n");
      adjustedBody = replacePlacemarkers(adjustedBody, adjustedUrl, requestedFormat);
      int payloadMax = 3600;
      int bodyLimit = Math.max(0, payloadMax - (adjustedTitle == null ? 0 : adjustedTitle.length()));
      adjustedTitle = truncate(adjustedTitle, payloadMax);
      adjustedBody = truncate(adjustedBody, bodyLimit);
    } else if (isDiscord(adjustedUrl) && requestedFormat.contains("html")) {
      adjustedBody = adjustedBody.strip().replace("<br>", "\n").replace("</br>", "\n");
      adjustedBody = adjustedBody.replace("&nbsp;", " ");
      adjustedBody = NEWLINES.matcher(adjustedBody).replaceAll("\n");
      if (requestedFormat.equals("html")) {
        adjustedBody = replacePlacemarkers(adjustedBody, adjustedUrl, requestedFormat);
        int payloadMax = 1700;
        int bodyLimit =
            Math.max(0, payloadMax - (adjustedTitle == null ? 0 : adjustedTitle.length()));
        adjustedTitle = truncate(adjustedTitle, payloadMax);
        adjustedBody = truncate(adjustedBody, bodyLimit);
      }
    } else if (requestedFormat.equals("htmlcolor") || requestedFormat.equals("html")) {
      adjustedBody = replacePlacemarkers(adjustedBody, adjustedUrl, requestedFormat);
      adjustedBody = NEWLINES.matcher(adjustedBody).replaceAll("<br>\n");
    } else {
      adjustedBody = replacePlacemarkers(adjustedBody, adjustedUrl, requestedFormat);
    }

    return new Result(adjustedUrl, adjustedBody, adjustedTitle);
  }

  private static String truncate(String text, int limit) {
    if (text == null) {
      return null;
    }
    return text.length() <= limit ? text : text.substring(0, limit);
  }

  private static boolean isDiscord(String url) {
    return url.startsWith("discord://")
        || url.startsWith("https://discordapp.com/api/webhooks")
        || url.startsWith("https://discord.com/api");
  }

  private static String lowercaseScheme(String url) {
    int at = url.indexOf("://");
    return at > 0 ? url.substring(0, at).toLowerCase(Locale.ROOT) + url.substring(at) : url;
  }

  /** The markers turned into whatever the destination understands. */
  public static String replacePlacemarkers(String text, String url, String requestedFormat) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    if (url.startsWith("tgram://")) {
      return text
          .replace(DiffRenderer.REMOVED_OPEN, "<s>")
          .replace(DiffRenderer.REMOVED_CLOSED, "</s>")
          .replace(DiffRenderer.ADDED_OPEN, "<b>")
          .replace(DiffRenderer.ADDED_CLOSED, "</b>")
          .replace(DiffRenderer.CHANGED_OPEN, "<s>")
          .replace(DiffRenderer.CHANGED_CLOSED, "</s>")
          .replace(DiffRenderer.CHANGED_INTO_OPEN, "<b>")
          .replace(DiffRenderer.CHANGED_INTO_CLOSED, "</b>");
    }
    if (isDiscord(url) && requestedFormat.equals("html")) {
      return applyLightweightMarkup(text);
    }
    if (requestedFormat.equals("htmlcolor")) {
      return text
          .replace(
              DiffRenderer.REMOVED_OPEN,
              "<span style=\"" + DiffRenderer.REMOVED_STYLE
                  + "\" role=\"deletion\" aria-label=\"Removed text\" title=\"Removed text\">")
          .replace(DiffRenderer.REMOVED_CLOSED, "</span>")
          .replace(
              DiffRenderer.ADDED_OPEN,
              "<span style=\"" + DiffRenderer.ADDED_STYLE
                  + "\" role=\"insertion\" aria-label=\"Added text\" title=\"Added text\">")
          .replace(DiffRenderer.ADDED_CLOSED, "</span>")
          .replace(
              DiffRenderer.CHANGED_OPEN,
              "<span style=\"" + DiffRenderer.CHANGED_STYLE
                  + "\" role=\"note\" aria-label=\"Changed text\" title=\"Changed text\">")
          .replace(DiffRenderer.CHANGED_CLOSED, "</span>")
          .replace(
              DiffRenderer.CHANGED_INTO_OPEN,
              "<span style=\"" + DiffRenderer.CHANGED_INTO_STYLE
                  + "\" role=\"note\" aria-label=\"Changed into\" title=\"Changed into\">")
          .replace(DiffRenderer.CHANGED_INTO_CLOSED, "</span>");
    }
    if (requestedFormat.equals("markdown")) {
      return applyStandardMarkup(text);
    }
    return text
        .replace(DiffRenderer.REMOVED_OPEN, "(removed) ")
        .replace(DiffRenderer.REMOVED_CLOSED, "")
        .replace(DiffRenderer.ADDED_OPEN, "(added) ")
        .replace(DiffRenderer.ADDED_CLOSED, "")
        .replace(DiffRenderer.CHANGED_OPEN, "(changed) ")
        .replace(DiffRenderer.CHANGED_CLOSED, "")
        .replace(DiffRenderer.CHANGED_INTO_OPEN, "(into) ")
        .replace(DiffRenderer.CHANGED_INTO_CLOSED, "");
  }

  /**
   * The markers swapped for the lighter markup, with the whitespace kept outside the markers.
   *
   * <p>Putting the markers around the whitespace instead would break them: that markup only
   * takes effect when its marker is immediately next to the text it marks.
   */
  private static String applyLightweightMarkup(String text) {
    String out = text;
    out = wrap(out, DiffRenderer.REMOVED_OPEN, "~~", DiffRenderer.REMOVED_CLOSED, "~~");
    out = wrap(out, DiffRenderer.ADDED_OPEN, "**", DiffRenderer.ADDED_CLOSED, "**");
    out = wrap(out, DiffRenderer.CHANGED_OPEN, "~~", DiffRenderer.CHANGED_CLOSED, "~~");
    out = wrap(out, DiffRenderer.CHANGED_INTO_OPEN, "**", DiffRenderer.CHANGED_INTO_CLOSED, "**");
    return out;
  }

  private static String applyStandardMarkup(String text) {
    String out = text;
    out = wrap(out, DiffRenderer.REMOVED_OPEN, "<del>", DiffRenderer.REMOVED_CLOSED, "</del>");
    out = wrap(out, DiffRenderer.ADDED_OPEN, "**", DiffRenderer.ADDED_CLOSED, "**");
    out = wrap(out, DiffRenderer.CHANGED_OPEN, "<del>", DiffRenderer.CHANGED_CLOSED, "</del>");
    out = wrap(out, DiffRenderer.CHANGED_INTO_OPEN, "**", DiffRenderer.CHANGED_INTO_CLOSED, "**");
    return out;
  }

  private static String wrap(
      String text, String openTag, String openMarkup, String closeTag, String closeMarkup) {
    Pattern pattern =
        Pattern.compile(
            Pattern.quote(openTag) + "(\\s*)(.*?)?(\\s*)" + Pattern.quote(closeTag),
            Pattern.DOTALL);
    Matcher matcher = pattern.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String leading = matcher.group(1) == null ? "" : matcher.group(1);
      String content = matcher.group(2) == null ? "" : matcher.group(2);
      String trailing = matcher.group(3) == null ? "" : matcher.group(3);
      matcher.appendReplacement(
          sb,
          Matcher.quoteReplacement(leading + openMarkup + content + closeMarkup + trailing));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  /** The formats that mean the body is markup. */
  public static boolean isHtmlFormat(String format) {
    return format != null && format.contains("html");
  }

  /** The list of formats a body may be written in. */
  public static List<String> formats() {
    return List.of("text", "html", "htmlcolor", "markdown");
  }
}
