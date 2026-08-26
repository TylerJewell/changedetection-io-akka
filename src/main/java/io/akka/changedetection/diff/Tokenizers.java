package io.akka.changedetection.diff;

import java.util.ArrayList;
import java.util.List;

/** The two ways a line is cut into pieces before two lines are compared word by word. */
public final class Tokenizers {

  private Tokenizers() {}

  /** Whitespace is a boundary and is itself a token, so a diff can be reassembled exactly. */
  public static List<String> words(String text) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        tokens.add(String.valueOf(c));
      } else {
        current.append(c);
      }
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  /** As above, but a tag is one token, so a changed attribute does not split the tag. */
  public static List<String> wordsAndHtml(String text) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inTag = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '<') {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        current.append('<');
        inTag = true;
      } else if (c == '>' && inTag) {
        current.append('>');
        tokens.add(current.toString());
        current.setLength(0);
        inTag = false;
      } else if (!inTag && (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85)) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        tokens.add(String.valueOf(c));
      } else {
        current.append(c);
      }
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  public static List<String> byName(String name, String text) {
    return "words".equals(name) ? words(text) : wordsAndHtml(text);
  }
}
