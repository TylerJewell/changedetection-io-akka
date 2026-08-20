package io.akka.changedetection.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Turning a fetched body into text the rules can compare. SPEC-001 §3 R9, R10, R11, R17. */
public final class TextPreparation {

  private static final Pattern SCRIPT_OR_STYLE =
      Pattern.compile(
          "<(script|style)\\b[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
  private static final Pattern BLOCK_END =
      Pattern.compile(
          "</(p|div|li|tr|h[1-6]|section|article|header|footer|blockquote|pre|table|ul|ol)>"
              + "|<br\\s*/?>",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);
  private static final Pattern HORIZONTAL_RUN = Pattern.compile("[^\\S\\n]+");
  private static final Pattern ALL_WHITESPACE = Pattern.compile("\\s+");

  private TextPreparation() {}

  /** SPEC-001 §3 R9. */
  public static String toText(String body, ContentType contentType) {
    if (contentType != ContentType.HTML) {
      return body;
    }
    String stripped = SCRIPT_OR_STYLE.matcher(body).replaceAll(" ");
    stripped = COMMENT.matcher(stripped).replaceAll(" ");
    stripped = BLOCK_END.matcher(stripped).replaceAll("\n");
    stripped = TAG.matcher(stripped).replaceAll("");
    stripped = unescape(stripped);
    // Runs of horizontal whitespace collapse, but the line breaks the block ends produced stay:
    // every ignore, trigger and forbidden rule matches per line, so the lines have to survive.
    stripped = HORIZONTAL_RUN.matcher(stripped).replaceAll(" ");
    List<String> lines = new ArrayList<>();
    for (String line : stripped.split("\n", -1)) {
      lines.add(line.strip());
    }
    return String.join("\n", lines).strip();
  }

  /**
   * SPEC-001 §3 R10. The order of the lines that remain is the order of the input — stated as a
   * rule because the checksum is order-sensitive and the source leaves it to a data structure.
   */
  public static String stripIgnored(String text, List<String> ignorePatterns) {
    List<String> needles = new ArrayList<>();
    for (String pattern : ignorePatterns) {
      if (pattern != null && !pattern.isBlank()) {
        needles.add(pattern.strip().toLowerCase());
      }
    }
    if (needles.isEmpty()) {
      return text;
    }
    List<String> kept = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      String lowered = line.toLowerCase();
      if (needles.stream().noneMatch(lowered::contains)) {
        kept.add(line);
      }
    }
    return String.join("\n", kept);
  }

  /** True when any pattern appears anywhere in the text, case-insensitively. */
  public static boolean containsAny(String text, List<String> patterns) {
    String lowered = text.toLowerCase();
    return patterns.stream()
        .filter(p -> p != null && !p.isBlank())
        .anyMatch(p -> lowered.contains(p.strip().toLowerCase()));
  }

  /** SPEC-001 §3 R17. */
  public static String checksum(String text, boolean ignoreWhitespace) {
    return md5(ignoreWhitespace ? ALL_WHITESPACE.matcher(text).replaceAll("") : text);
  }

  public static String md5(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is required of every Java platform", e);
    }
  }

  private static String unescape(String text) {
    return text.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
  }
}
