package io.akka.changedetection.text.inscriptis;

import io.akka.changedetection.text.inscriptis.HtmlProperties.HorizontalAlignment;
import io.akka.changedetection.text.inscriptis.HtmlProperties.VerticalAlignment;
import java.util.ArrayList;
import java.util.List;

/**
 * One cell, which is a canvas of its own until the table it belongs to is laid out.
 *
 * <p>A cell is written like any other part of the page, and only once the whole table is closed
 * does it learn the column width and row height it has to fill -- so its own content is fixed
 * first and padded afterwards.
 */
public final class TableCell extends Canvas {

  private HorizontalAlignment align;
  private VerticalAlignment valign;

  private List<String> contentBlocks = new ArrayList<>();
  private boolean normalized = false;
  private int requestedWidth = 0;
  private int requestedHeight = 0;
  private List<String> renderedBlocks;

  public TableCell(HorizontalAlignment align, VerticalAlignment valign) {
    this.align = align;
    this.valign = valign;
  }

  /** Splits multi-line content into one-line blocks and freezes it. Returns the line count. */
  public int normalizeBlocks() {
    flushInline();
    List<String> out = new ArrayList<>();
    for (String line : blocks) {
      for (String part : line.split("\n", -1)) {
        out.add(part);
      }
    }
    if (out.isEmpty()) {
      out.add("");
    }
    contentBlocks = out;
    normalized = true;
    return contentBlocks.size();
  }

  private int contentWidth() {
    if (!normalized) {
      throw new IllegalStateException(
          "cell width read before the cell was normalized; the width would not be final");
    }
    int max = 0;
    for (String line : contentBlocks) {
      max = Math.max(max, line.length());
    }
    return max;
  }

  public List<String> renderedOrContentBlocks() {
    if (requestedWidth > 0 || requestedHeight > contentBlocks.size()) {
      return renderedBlocks();
    }
    return contentBlocks;
  }

  private List<String> renderedBlocks() {
    if (renderedBlocks != null) {
      return renderedBlocks;
    }
    String emptyLine = " ".repeat(width());
    List<String> out = new ArrayList<>();
    int top = topPadding();
    for (int i = 0; i < top; i++) {
      out.add(emptyLine);
    }
    for (String line : contentBlocks) {
      out.add(align.format(line, width()));
    }
    for (int i = 0; i < requestedHeight - contentBlocks.size() - top; i++) {
      out.add(emptyLine);
    }
    renderedBlocks = out;
    return out;
  }

  public int width() {
    return Math.max(contentWidth(), requestedWidth);
  }

  public void setWidth(int width) {
    if (width < contentWidth()) {
      throw new IllegalArgumentException(
          "cell width " + width + " is below its content width " + contentWidth());
    }
    if (width != requestedWidth) {
      requestedWidth = width;
      renderedBlocks = null;
    }
  }

  public int height() {
    return Math.max(contentBlocks.size(), requestedHeight);
  }

  public void setHeight(int height) {
    if (height < contentBlocks.size()) {
      throw new IllegalArgumentException(
          "cell height " + height + " is below its content height " + contentBlocks.size());
    }
    if (height != requestedHeight) {
      requestedHeight = height;
      renderedBlocks = null;
    }
  }

  private int topPadding() {
    return (height() - contentBlocks.size()) * valign.value() / 2;
  }
}
