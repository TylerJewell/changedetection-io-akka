package io.akka.changedetection.text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The regular expressions a user types, compiled the way the original compiles them.
 *
 * <p>Every filter, ignore rule and trigger in the original may be written either as plain text
 * or in the slash-enclosed form {@code /body/flags}. That form is not a regular expression
 * feature -- it is a convention the original translates into an inline flag group before
 * compiling -- so the translation is part of the behaviour and lives here.
 */
public final class PyRegex {

  public static final Pattern PERL_STYLE_REGEX = Pattern.compile("^/(.*?)/([a-z]*)?$");

  private PyRegex() {}

  /** True when the string is in the slash-enclosed form the original treats as a regex. */
  public static boolean isPerlStyle(String s) {
    return PERL_STYLE_REGEX.matcher(s).find();
  }

  /**
   * The slash-enclosed form turned into an inline-flag expression, with case-insensitive as the
   * fallback for anything that is not in that form at all.
   */
  public static String perlStyleToOptions(String regex) {
    Matcher m = PERL_STYLE_REGEX.matcher(regex);
    if (m.find()) {
      String flags = m.group(2);
      if (flags == null || flags.isEmpty()) {
        flags = "i";
      }
      return "(?" + flags + ")" + m.group(1);
    }
    return "(?i)" + regex;
  }

  /** The flag letters on a slash-enclosed expression, or "i" when it carries none. */
  public static String perlStyleFlags(String regex) {
    Matcher m = PERL_STYLE_REGEX.matcher(regex);
    if (m.find()) {
      String flags = m.group(2);
      return (flags == null || flags.isEmpty()) ? "i" : flags;
    }
    return "i";
  }

  /**
   * Compiles a pattern written for Python. Named groups differ in spelling and two of Python's
   * flag letters have no counterpart, so those are translated rather than being handed to the
   * compiler as-is, where they would raise on an expression the original accepts.
   */
  public static Pattern compile(String pythonPattern) {
    return Pattern.compile(translate(pythonPattern));
  }

  public static Pattern compile(String pythonPattern, int extraFlags) {
    return Pattern.compile(translate(pythonPattern), extraFlags);
  }

  public static String translate(String pythonPattern) {
    String out = pythonPattern.replace("(?P<", "(?<").replace("(?P=", "\\k<");
    return stripUnsupportedInlineFlags(out);
  }

  private static String stripUnsupportedInlineFlags(String pattern) {
    // Python's 'a' (ASCII) and 'L' (locale) have no inline spelling in Java; the rest do.
    Matcher m = Pattern.compile("\\(\\?([aiLmsux]+)\\)").matcher(pattern);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String flags = m.group(1).replace("a", "").replace("L", "");
      m.appendReplacement(sb, flags.isEmpty() ? "" : Matcher.quoteReplacement("(?" + flags + ")"));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** True when a slash-enclosed expression asked for dot-matches-all or multi-line. */
  public static boolean isMultilineFlavour(String perlStyle) {
    String flags = perlStyleFlags(perlStyle).toLowerCase(Locale.ROOT);
    return flags.indexOf('s') >= 0 || flags.indexOf('m') >= 0;
  }
}
