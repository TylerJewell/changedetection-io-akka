package io.akka.changedetection.text.inscriptis;

/** The rendering properties an element carries: how it displays, and how it treats whitespace. */
public final class HtmlProperties {

  private HtmlProperties() {}

  public enum Display {
    INLINE,
    BLOCK,
    NONE
  }

  public enum WhiteSpace {
    /** Runs of whitespace collapse into one. */
    NORMAL,
    /** Runs of whitespace are kept as they are. */
    PRE
  }

  public enum HorizontalAlignment {
    LEFT,
    RIGHT,
    CENTER;

    /** Python's str.ljust / str.rjust / str.center, including center's odd-width rule. */
    public String format(String text, int width) {
      int margin = width - text.length();
      if (margin <= 0) {
        return text;
      }
      switch (this) {
        case LEFT:
          return text + " ".repeat(margin);
        case RIGHT:
          return " ".repeat(margin) + text;
        default:
          int left = margin / 2 + (margin & width & 1);
          return " ".repeat(left) + text + " ".repeat(margin - left);
      }
    }
  }

  public enum VerticalAlignment {
    TOP(0),
    MIDDLE(1),
    BOTTOM(2);

    private final int value;

    VerticalAlignment(int value) {
      this.value = value;
    }

    public int value() {
      return value;
    }
  }
}
