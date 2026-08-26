package io.akka.changedetection.processors;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What kind of document came back, decided from the header, the body, and a signature scan.
 *
 * <p>The kind decides what happens to the body: a feed has its embedded markup unwrapped, a
 * document is converted, markup is turned into text, and plain text is left alone. So a wrong
 * answer here does not degrade the comparison, it compares something else entirely -- which is
 * why the header alone is not trusted. Servers routinely label a feed as plain text.
 *
 * <p>The order of the tests is the behaviour. The header wins for plain text and for the feed
 * and JSON families; the body wins for a feed the header mislabelled; and the signature scan is
 * consulted only for kinds the body cannot be read for.
 */
public final class StreamType {

  private static final List<String> RSS_XML_CONTENT_TYPES =
      List.of(
          "application/rss+xml", "application/rdf+xml", "application/atom+xml", "text/rss+xml",
          "application/x-rss+xml", "application/x-atom+xml");

  private static final List<String> JSON_CONTENT_TYPES =
      List.of(
          "application/activity+json", "application/feed+json", "application/json",
          "application/ld+json", "application/vnd.api+json");

  private static final List<String> XML_CONTENT_TYPES = List.of("text/xml", "application/xml");

  private static final List<String> HTML_PATTERNS =
      List.of("<!doctype html", "<html", "<head", "<body", "<script", "<iframe", "<div");

  private static final Pattern OPEN_TAG_WHITESPACE = Pattern.compile("<\\s+");
  private static final Pattern JSONP_START = Pattern.compile("^\\w[\\w.]*\\s*\\(");

  public boolean isPdf;
  public boolean isJson;
  public boolean isHtml;
  public boolean isPlaintext;
  public boolean isRss;
  public boolean isCsv;
  public boolean isXml;
  public boolean isYaml;

  private StreamType() {}

  public static StreamType guess(String httpContentHeader, String content) {
    StreamType type = new StreamType();
    String header = httpContentHeader == null ? "" : httpContentHeader;
    String body = content == null ? "" : content;
    String testContent =
        (body.length() > 200 ? body.substring(0, 200) : body).toLowerCase(Locale.ROOT).strip();
    String normalised = OPEN_TAG_WHITESPACE.matcher(testContent).replaceAll("<");

    // The signature scan sees only the first bytes, as the original's does; its answer is
    // taken for kinds a body cannot be read for, and ignored where it would merely repeat
    // what the content tests below already decide better.
    String magicHeader = header;
    String magicResult = Signature.detect(body);
    if (magicResult != null
        && !magicResult.equals("application/octet-stream")
        && !magicResult.equals("application/x-empty")
        && !magicResult.equals("binary")
        && !magicResult.equals("text/html")
        && !magicResult.equals("text/plain")) {
      magicHeader = magicResult;
    }

    boolean hasHtmlPatterns = false;
    for (String pattern : HTML_PATTERNS) {
      if (normalised.contains(pattern)) {
        hasHtmlPatterns = true;
        break;
      }
    }

    if (header.contains("text/plain")) {
      type.isPlaintext = true;
    }

    if (containsAny(header, RSS_XML_CONTENT_TYPES)) {
      type.isRss = true;
    } else if (containsAny(header, JSON_CONTENT_TYPES)) {
      // A server may declare JSON and send a callback-wrapped document, which is not JSON and
      // would fail to parse; treated as text so the check still has something to compare.
      if (JSONP_START.matcher(testContent).find()) {
        type.isPlaintext = true;
      } else {
        type.isJson = true;
      }
    } else if (magicHeader.contains("pdf")) {
      type.isPdf = true;
    } else if (normalised.contains("<rss")
        || normalised.contains("<feed")
        || containsAny(magicHeader, RSS_XML_CONTENT_TYPES)
        || normalised.contains("<rdf:")) {
      type.isRss = true;
    } else if (hasHtmlPatterns || header.split(";")[0].strip().equals("text/html")) {
      type.isHtml = true;
    } else if (containsAny(magicHeader, JSON_CONTENT_TYPES)) {
      type.isJson = true;
    } else if (containsAny(header, XML_CONTENT_TYPES)) {
      if (!type.isRss) {
        type.isXml = true;
      }
    } else if (normalised.startsWith("<?xml") || containsAny(magicHeader, XML_CONTENT_TYPES)) {
      type.isXml = true;
    } else if (testContent.contains("%pdf-1")) {
      type.isPdf = true;
    } else if (header.startsWith("text/")) {
      type.isPlaintext = true;
    } else if (magicHeader.contains("text")) {
      type.isPlaintext = true;
    } else if ("text/plain".equals(magicResult)) {
      type.isPlaintext = true;
    }

    return type;
  }

  private static boolean containsAny(String haystack, List<String> needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  /**
   * What the first bytes of a document say it is, or nothing when they say nothing.
   *
   * <p>Answering "plain text" where the scan recognised nothing would be a claim, and the
   * caller reads that claim as an answer: a JSON document served with no content type at all
   * would come out as text, and be compared without being reformatted.
   */
  static final class Signature {
    private Signature() {}

    static String detect(String content) {
      if (content == null || content.isEmpty()) {
        return "application/x-empty";
      }
      String head = content.length() > 200 ? content.substring(0, 200) : content;
      String trimmed = head.strip();
      if (head.startsWith("%PDF-")) {
        return "application/pdf";
      }
      if (head.startsWith("GIF87a") || head.startsWith("GIF89a")) {
        return "image/gif";
      }
      if (!trimmed.isEmpty() && (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[')) {
        return "application/json";
      }
      String lowered = trimmed.toLowerCase(java.util.Locale.ROOT);
      if (lowered.startsWith("<?xml")) {
        return "text/xml";
      }
      if (lowered.startsWith("<!doctype html") || lowered.startsWith("<html")) {
        return "text/html";
      }
      return null;
    }
  }
}
