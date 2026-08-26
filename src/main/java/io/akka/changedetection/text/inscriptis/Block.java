package io.akka.changedetection.text.inscriptis;

import io.akka.changedetection.text.inscriptis.HtmlProperties.WhiteSpace;
import org.jsoup.parser.Parser;

/** The line currently being written. */
public final class Block {

  public int idx;
  public Prefix prefix;
  private String content = "";
  private boolean collapsableWhitespace = true;

  public Block(int idx, Prefix prefix) {
    this.idx = idx;
    this.prefix = prefix;
  }

  public void merge(String text, WhiteSpace whitespace) {
    if (whitespace == WhiteSpace.PRE) {
      mergePreText(text);
    } else {
      mergeNormalText(text);
    }
  }

  private void mergeNormalText(String text) {
    StringBuilder normalized = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (!isPythonSpace(ch)) {
        normalized.append(ch);
        collapsableWhitespace = false;
      } else if (!collapsableWhitespace) {
        normalized.append(' ');
        collapsableWhitespace = true;
      }
    }

    if (normalized.length() > 0) {
      String merged = content.isEmpty() ? prefix.first() + normalized : normalized.toString();
      merged = Parser.unescapeEntities(merged, false);
      content += merged;
      idx += merged.length();
    }
  }

  private void mergePreText(String text) {
    String merged = prefix.first() + text.replace("\n", "\n" + prefix.rest());
    merged = Parser.unescapeEntities(merged, false);
    content += merged;
    idx += merged.length();
    collapsableWhitespace = false;
  }

  /**
   * Whitespace as the original counts it, which includes the non-breaking space.
   *
   * <p>This one character decides whether whitespace collapses. A page that writes its
   * indentation with non-breaking spaces -- and the original's own interface is one -- comes
   * out with that indentation kept if the test says no and dropped if it says yes, so the two
   * sides disagree on the text of every such line and therefore on its checksum.
   */
  private static boolean isPythonSpace(char c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
  }

  public boolean isEmpty() {
    return content().isEmpty();
  }

  /** A trailing collapsable space belongs to no line, so reading the content drops it. */
  public String content() {
    if (!collapsableWhitespace) {
      return content;
    }
    if (content.endsWith(" ")) {
      content = content.substring(0, content.length() - 1);
      idx -= 1;
    }
    return content;
  }

  public Block newBlock() {
    prefix.setConsumed(false);
    return new Block(idx + 1, prefix);
  }
}
