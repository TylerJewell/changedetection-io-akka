package io.akka.changedetection.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;

/**
 * Markup broken into the pieces the original's parsers see.
 *
 * <p>Both of the original's parsers -- the one that selects and the one that renders -- read
 * the same tokens and differ only in the tree they build from them. Sharing the tokenizer
 * keeps that difference to the one thing it actually is, rather than two separate parsers that
 * also happen to disagree about how an unquoted attribute ends.
 */
public final class Tokenizer {

  /** What the tokenizer emits. */
  public sealed interface Token {}

  public record Text(String content) implements Token {}

  public record StartTag(String name, Map<String, String> attributes, boolean selfClosing)
      implements Token {}

  public record EndTag(String name) implements Token {}

  public record CommentToken(String content) implements Token {}

  public record Declaration(String content) implements Token {}

  public record ProcessingInstruction(String content) implements Token {}

  /** Elements whose content is text however much it looks like markup. */
  public static final List<String> RAW_TEXT_ELEMENTS =
      List.of("script", "style", "textarea", "title");

  private static final Pattern TAG_NAME = Pattern.compile("[a-zA-Z][^\\t\\n\\r\\f />\\u0000]*");
  private static final Pattern ATTRIBUTE =
      Pattern.compile(
          "([^\\s/>][^\\s/=>]*)(\\s*=\\s*(\\'[^\\']*\\'|\\\"[^\\\"]*\\\"|[^\\s>]*))?");

  private Tokenizer() {}

  public static List<Token> tokenize(String html) {
    List<Token> tokens = new ArrayList<>();
    if (html == null || html.isEmpty()) {
      return tokens;
    }
    int i = 0;
    int n = html.length();
    StringBuilder text = new StringBuilder();
    String rawTextElement = null;

    while (i < n) {
      if (rawTextElement != null) {
        String closer = "</" + rawTextElement;
        int end = indexOfIgnoreCase(html, closer, i);
        if (end < 0) {
          text.append(html, i, n);
          i = n;
        } else {
          text.append(html, i, end);
          i = end;
        }
        flushRawText(tokens, text);
        rawTextElement = null;
        continue;
      }

      char c = html.charAt(i);
      if (c != '<') {
        text.append(c);
        i++;
        continue;
      }

      if (i + 1 >= n) {
        text.append(c);
        i++;
        continue;
      }

      char next = html.charAt(i + 1);
      if (next == '!') {
        if (html.startsWith("<!--", i)) {
          int end = html.indexOf("-->", i + 4);
          flushText(tokens, text);
          if (end < 0) {
            tokens.add(new CommentToken(html.substring(i + 4)));
            i = n;
          } else {
            tokens.add(new CommentToken(html.substring(i + 4, end)));
            i = end + 3;
          }
          continue;
        }
        int end = html.indexOf('>', i);
        flushText(tokens, text);
        if (end < 0) {
          tokens.add(new Declaration(html.substring(i + 2)));
          i = n;
        } else {
          tokens.add(new Declaration(html.substring(i + 2, end)));
          i = end + 1;
        }
        continue;
      }

      if (next == '?') {
        int end = html.indexOf('>', i);
        flushText(tokens, text);
        if (end < 0) {
          tokens.add(new ProcessingInstruction(html.substring(i + 2)));
          i = n;
        } else {
          tokens.add(new ProcessingInstruction(html.substring(i + 2, end)));
          i = end + 1;
        }
        continue;
      }

      if (next == '/') {
        Matcher m = TAG_NAME.matcher(html);
        if (m.find(i + 2) && m.start() == i + 2) {
          int end = html.indexOf('>', m.end());
          if (end < 0) {
            text.append(c);
            i++;
            continue;
          }
          flushText(tokens, text);
          tokens.add(new EndTag(m.group().toLowerCase(Locale.ROOT)));
          i = end + 1;
          continue;
        }
        int end = html.indexOf('>', i);
        if (end < 0) {
          text.append(c);
          i++;
          continue;
        }
        i = end + 1;
        continue;
      }

      Matcher m = TAG_NAME.matcher(html);
      if (!m.find(i + 1) || m.start() != i + 1) {
        text.append(c);
        i++;
        continue;
      }

      int cursor = m.end();
      String name = m.group().toLowerCase(Locale.ROOT);
      Map<String, String> attributes = new LinkedHashMap<>();
      boolean selfClosing = false;
      boolean closed = false;

      while (cursor < n) {
        while (cursor < n && Character.isWhitespace(html.charAt(cursor))) {
          cursor++;
        }
        if (cursor >= n) {
          break;
        }
        char ch = html.charAt(cursor);
        if (ch == '>') {
          cursor++;
          closed = true;
          break;
        }
        if (ch == '/' && cursor + 1 < n && html.charAt(cursor + 1) == '>') {
          selfClosing = true;
          cursor += 2;
          closed = true;
          break;
        }
        if (ch == '/') {
          cursor++;
          continue;
        }
        Matcher a = ATTRIBUTE.matcher(html);
        if (!a.find(cursor) || a.start() != cursor) {
          cursor++;
          continue;
        }
        String key = a.group(1).toLowerCase(Locale.ROOT);
        String value = a.group(3);
        if (value != null) {
          if (value.length() >= 2
              && ((value.charAt(0) == '"' && value.endsWith("\""))
                  || (value.charAt(0) == '\'' && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1);
          }
          value = Parser.unescapeEntities(value, true);
        }
        attributes.putIfAbsent(key, value);
        cursor = a.end();
      }

      if (!closed) {
        text.append(c);
        i++;
        continue;
      }

      flushText(tokens, text);
      tokens.add(new StartTag(name, attributes, selfClosing));
      i = cursor;
      if (!selfClosing && RAW_TEXT_ELEMENTS.contains(name)) {
        rawTextElement = name;
      }
    }

    flushText(tokens, text);
    return tokens;
  }

  private static void flushText(List<Token> tokens, StringBuilder text) {
    if (text.length() == 0) {
      return;
    }
    // The original's tokenizer resolves character references as it reads, so by the time a
    // tree exists there are no entities left in it. Anything written back out is escaped
    // afresh, which is why a document that went in with a named entity comes out numeric or
    // literal -- a difference the checksum sees.
    tokens.add(new Text(Parser.unescapeEntities(text.toString(), false)));
    text.setLength(0);
  }

  private static void flushRawText(List<Token> tokens, StringBuilder text) {
    if (text.length() == 0) {
      return;
    }
    tokens.add(new Text(text.toString()));
    text.setLength(0);
  }

  private static int indexOfIgnoreCase(String haystack, String needle, int from) {
    int limit = haystack.length() - needle.length();
    for (int i = from; i <= limit; i++) {
      if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
        return i;
      }
    }
    return -1;
  }
}
