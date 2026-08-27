package io.akka.changedetection.notification;

import io.akka.changedetection.jinja.Filters;

/**
 * The wrapper an electronic mail body is put in.
 *
 * <p>A difference is a column of aligned text, and every mail reader would otherwise reflow it
 * into a paragraph and lose the alignment -- so the body goes inside a preformatted block with
 * a fixed-width family named several ways, because the readers disagree about which name they
 * honour.
 *
 * <p>Line breaks are removed first: the body already carries its own break markup by this
 * point, and a preformatted block would render both, doubling every line gap.
 */
public final class EmailLayout {

  private EmailLayout() {}

  public static String asMonospacedHtml(String content, String title) {
    String body = content == null ? "" : content.replace("\r", "").replace("\n", "");
    String heading = title == null ? "" : Filters.escapeHtml(title);
    return "<!DOCTYPE html>\n"
        + "<html lang=\"en\">\n"
        + "<head>\n"
        + "  <meta charset=\"UTF-8\">\n"
        + "  <meta name=\"x-apple-disable-message-reformatting\">\n"
        + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
        + "  <!--[if mso]>\n"
        + "    <style>\n"
        + "      body, div, pre, td { font-family: \"Courier New\", Courier, monospace !important; }\n"
        + "    </style>\n"
        + "  <![endif]-->\n"
        + "  <title>" + heading + "</title>\n"
        + "</head>\n"
        + "<body style=\"-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;\">\n"
        + "  <pre role=\"article\" aria-roledescription=\"email\" lang=\"en\"\n"
        + "       style=\"font-family: monospace, 'Courier New', Courier; font-size: 0.9rem;\n"
        + "              white-space: pre-wrap; word-break: break-word;\">" + body + "</pre>\n"
        + "</body>\n"
        + "</html>";
  }
}
