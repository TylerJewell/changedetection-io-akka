package io.akka.changedetection.text.inscriptis;

import io.akka.changedetection.text.inscriptis.HtmlProperties.Display;
import io.akka.changedetection.text.inscriptis.HtmlProperties.HorizontalAlignment;
import io.akka.changedetection.text.inscriptis.HtmlProperties.VerticalAlignment;
import io.akka.changedetection.text.inscriptis.HtmlProperties.WhiteSpace;

/**
 * One open element and the style in force inside it.
 *
 * <p>Mutable and copied per open tag rather than shared, because a tag handler edits its own
 * entry -- a list item writes its bullet onto the element that is about to be opened.
 */
public final class StyledElement {

  public Canvas canvas;
  public String tag;
  public String prefix;
  public String suffix;
  public Display display;
  public int marginBefore;
  public int marginAfter;
  public int paddingInline;
  public String listBullet;
  /** An ordered list carries a number here instead of a string bullet. */
  public Integer listCounter;

  public WhiteSpace whitespace;
  public boolean limitWhitespaceAffixes;
  public HorizontalAlignment align;
  public VerticalAlignment valign;
  public int previousMarginAfter;

  public StyledElement() {
    this("default", "", "", Display.INLINE, 0, 0, 0, "", null, false);
  }

  public StyledElement(
      String tag,
      String prefix,
      String suffix,
      Display display,
      int marginBefore,
      int marginAfter,
      int paddingInline,
      String listBullet,
      WhiteSpace whitespace,
      boolean limitWhitespaceAffixes) {
    this.tag = tag;
    this.prefix = prefix;
    this.suffix = suffix;
    this.display = display;
    this.marginBefore = marginBefore;
    this.marginAfter = marginAfter;
    this.paddingInline = paddingInline;
    this.listBullet = listBullet;
    this.whitespace = whitespace;
    this.limitWhitespaceAffixes = limitWhitespaceAffixes;
    this.align = HorizontalAlignment.LEFT;
    this.valign = VerticalAlignment.MIDDLE;
    this.previousMarginAfter = 0;
  }

  public StyledElement copy() {
    StyledElement c = new StyledElement();
    c.canvas = canvas;
    c.tag = tag;
    c.prefix = prefix;
    c.suffix = suffix;
    c.display = display;
    c.marginBefore = marginBefore;
    c.marginAfter = marginAfter;
    c.paddingInline = paddingInline;
    c.listBullet = listBullet;
    c.listCounter = listCounter;
    c.whitespace = whitespace;
    c.limitWhitespaceAffixes = limitWhitespaceAffixes;
    c.align = align;
    c.valign = valign;
    c.previousMarginAfter = previousMarginAfter;
    return c;
  }

  public StyledElement setTag(String tag) {
    this.tag = tag;
    return this;
  }

  public StyledElement setCanvas(Canvas canvas) {
    this.canvas = canvas;
    return this;
  }

  public void write(String text) {
    if (text == null || text.isEmpty() || display == Display.NONE) {
      return;
    }
    canvas.write(this, prefix + text + suffix, null);
  }

  public void writeVerbatimText(String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    if (display == Display.BLOCK) {
      canvas.openBlock(this);
    }
    canvas.write(this, text, WhiteSpace.PRE);
    if (display == Display.BLOCK) {
      canvas.closeBlock(this);
    }
  }

  /**
   * The element about to be opened, with the enclosing context folded in: display:none is
   * inherited outright, an unset whitespace mode inherits, whitespace-only affixes are dropped
   * inside a pre region, and one block inside another remembers the outer block's bottom margin
   * so the two margins collapse rather than add.
   */
  public StyledElement refine(StyledElement next) {
    next.canvas = this.canvas;

    if (this.display == Display.NONE) {
      next.display = Display.NONE;
      return next;
    }

    if (next.whitespace == null) {
      next.whitespace = this.whitespace;
    }

    if (next.limitWhitespaceAffixes && this.whitespace == WhiteSpace.PRE) {
      if (isSpace(next.prefix)) {
        next.prefix = "";
      }
      if (isSpace(next.suffix)) {
        next.suffix = "";
      }
    }

    if (next.display == Display.BLOCK && this.display == Display.BLOCK) {
      next.previousMarginAfter = this.marginAfter;
    }

    return next;
  }

  /** Python's str.isspace(): false for the empty string, true when every character is space. */
  private static boolean isSpace(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isWhitespace(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
