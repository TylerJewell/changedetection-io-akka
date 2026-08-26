package io.akka.changedetection.text.inscriptis;

import io.akka.changedetection.text.inscriptis.HtmlProperties.Display;
import io.akka.changedetection.text.inscriptis.HtmlProperties.WhiteSpace;
import java.util.ArrayList;
import java.util.List;

/** The surface the page is written onto: finished lines, and the line in progress. */
public class Canvas {

  /**
   * Larger than any margin a document can ask for, so the first block writes no leading blank
   * lines: the margin is "already satisfied" until something has been written.
   */
  protected int margin = 1000;

  protected Block currentBlock = new Block(0, new Prefix());
  protected List<String> blocks = new ArrayList<>();

  public void openTag(StyledElement tag) {
    if (tag.display == Display.BLOCK) {
      openBlock(tag);
    }
  }

  public void openBlock(StyledElement tag) {
    if (!flushInline() && tag.listBullet != null && !tag.listBullet.isEmpty()) {
      writeUnconsumedBullet();
    }
    currentBlock.prefix.registerPrefix(tag.paddingInline, tag.listBullet);

    int requiredMargin = Math.max(tag.previousMarginAfter, tag.marginBefore);
    if (requiredMargin > margin) {
      int requiredNewlines = requiredMargin - margin;
      currentBlock.idx += requiredNewlines;
      blocks.add("\n".repeat(requiredNewlines - 1));
      margin = requiredMargin;
    }
  }

  public void writeUnconsumedBullet() {
    String bullet = currentBlock.prefix.unconsumedBullet();
    if (bullet != null && !bullet.isEmpty()) {
      blocks.add(bullet);
      currentBlock.idx += bullet.length();
      currentBlock = currentBlock.newBlock();
      margin = 0;
    }
  }

  public void write(StyledElement tag, String text, WhiteSpace whitespace) {
    currentBlock.merge(text, whitespace != null ? whitespace : tag.whitespace);
  }

  public void closeTag(StyledElement tag) {
    if (tag.display == Display.BLOCK) {
      if (!flushInline() && tag.listBullet != null && !tag.listBullet.isEmpty()) {
        writeUnconsumedBullet();
      }
      currentBlock.prefix.removeLastPrefix();
      closeBlock(tag);
    }
  }

  public void closeBlock(StyledElement tag) {
    if (tag.marginAfter > margin) {
      int requiredNewlines = tag.marginAfter - margin;
      currentBlock.idx += requiredNewlines;
      blocks.add("\n".repeat(requiredNewlines - 1));
      margin = tag.marginAfter;
    }
  }

  public void writeNewline() {
    if (!flushInline()) {
      blocks.add("");
      currentBlock = currentBlock.newBlock();
    }
  }

  public String getText() {
    flushInline();
    return String.join("\n", blocks);
  }

  public boolean flushInline() {
    if (!currentBlock.isEmpty()) {
      blocks.add(currentBlock.content());
      currentBlock = currentBlock.newBlock();
      margin = 0;
      return true;
    }
    return false;
  }

  public int leftMargin() {
    return currentBlock.prefix.currentPadding();
  }

  public Block currentBlock() {
    return currentBlock;
  }
}
