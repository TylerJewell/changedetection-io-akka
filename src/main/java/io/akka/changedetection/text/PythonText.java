package io.akka.changedetection.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * String operations with Python's semantics, where Java's differ in a way a rule can see.
 *
 * <p>Line splitting is the one that matters most: every ignore, trigger and forbidden rule in
 * the original is applied per line, and Python breaks a line at ten code points where splitting
 * on a line feed breaks at one. A page carrying a form feed or U+2028 is therefore a different
 * number of lines on each side, and every line-indexed rule shifts with it.
 */
public final class PythonText {

  /** The characters Python's str.strip() removes: its definition of whitespace. */
  private static final String PY_WHITESPACE = " \t\n\r\f";

  private static final int VT = 0x0B;
  private static final int FF = 0x0C;
  private static final int FS = 0x1C;
  private static final int GS = 0x1D;
  private static final int RS = 0x1E;
  private static final int NEL = 0x85;
  private static final int LINE_SEPARATOR = 0x2028;
  private static final int PARAGRAPH_SEPARATOR = 0x2029;

  private PythonText() {}

  private static boolean isLineBoundary(char c) {
    if (c == '\n' || c == '\r') {
      return true;
    }
    return c == VT
        || c == FF
        || c == FS
        || c == GS
        || c == RS
        || c == NEL
        || c == LINE_SEPARATOR
        || c == PARAGRAPH_SEPARATOR;
  }

  /** Python's str.splitlines(): no trailing empty element after a final line break. */
  public static List<String> splitLines(String s) {
    List<String> out = new ArrayList<>();
    if (s == null || s.isEmpty()) {
      return out;
    }
    int start = 0;
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (isLineBoundary(c)) {
        out.add(s.substring(start, i));
        if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
          i++;
        }
        i++;
        start = i;
      } else {
        i++;
      }
    }
    if (start < n) {
      out.add(s.substring(start, n));
    }
    return out;
  }

  /** Python's str.splitlines(keepends=True). */
  public static List<String> splitLinesKeepEnds(String s) {
    List<String> out = new ArrayList<>();
    if (s == null || s.isEmpty()) {
      return out;
    }
    int start = 0;
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (isLineBoundary(c)) {
        int end = i + 1;
        if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
          end++;
        }
        out.add(s.substring(start, end));
        i = end;
        start = i;
      } else {
        i++;
      }
    }
    if (start < n) {
      out.add(s.substring(start, n));
    }
    return out;
  }

  /** Python's str.strip() with no argument. */
  public static String strip(String s) {
    if (s == null) {
      return null;
    }
    int a = 0;
    int b = s.length();
    while (a < b && PY_WHITESPACE.indexOf(s.charAt(a)) >= 0) {
      a++;
    }
    while (b > a && PY_WHITESPACE.indexOf(s.charAt(b - 1)) >= 0) {
      b--;
    }
    return s.substring(a, b);
  }

  /** Python's str.rstrip() with no argument. */
  public static String rstrip(String s) {
    if (s == null) {
      return null;
    }
    int b = s.length();
    while (b > 0 && PY_WHITESPACE.indexOf(s.charAt(b - 1)) >= 0) {
      b--;
    }
    return s.substring(0, b);
  }

  /** Python's str.lstrip() with no argument. */
  public static String lstrip(String s) {
    if (s == null) {
      return null;
    }
    int a = 0;
    while (a < s.length() && PY_WHITESPACE.indexOf(s.charAt(a)) >= 0) {
      a++;
    }
    return s.substring(a);
  }

  /**
   * The original's TRANSLATE_WHITESPACE_TABLE: carriage return, line feed, tab and space are
   * deleted outright, and nothing else is touched. This is not "collapse whitespace" -- it is
   * what makes the ignore-whitespace checksum blind to indentation without being blind to a
   * non-breaking space.
   */
  public static String translateWhitespaceAway(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\r' && c != '\n' && c != '\t' && c != ' ') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Python's str.split() with no argument: split on runs of whitespace, no empty parts. */
  public static List<String> splitOnWhitespace(String s) {
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      while (i < n && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
      int start = i;
      while (i < n && !Character.isWhitespace(s.charAt(i))) {
        i++;
      }
      if (i > start) {
        out.add(s.substring(start, i));
      }
    }
    return out;
  }

  public static String md5Hex(String text) {
    return md5Hex(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String md5Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256Hex(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
