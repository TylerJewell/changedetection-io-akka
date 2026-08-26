package io.akka.changedetection.text.inscriptis;

import java.util.ArrayList;
import java.util.List;

/** A table, laid out once every cell it contains has been written. */
public final class Table {

  /** One row of cells, joined by the table's cell separator. */
  public static final class TableRow {
    public final List<TableCell> columns = new ArrayList<>();
    private final String cellSeparator;

    TableRow(String cellSeparator) {
      this.cellSeparator = cellSeparator;
    }

    String getText() {
      if (columns.isEmpty()) {
        return "";
      }
      int lines = Integer.MAX_VALUE;
      List<List<String>> blocks = new ArrayList<>();
      for (TableCell column : columns) {
        List<String> b = column.renderedOrContentBlocks();
        blocks.add(b);
        lines = Math.min(lines, b.size());
      }
      List<String> rowLines = new ArrayList<>();
      for (int i = 0; i < lines; i++) {
        List<String> parts = new ArrayList<>();
        for (List<String> b : blocks) {
          parts.add(b.get(i));
        }
        rowLines.add(String.join(cellSeparator, parts));
      }
      return String.join("\n", rowLines);
    }
  }

  private final List<TableRow> rows = new ArrayList<>();
  private final int leftMarginLen;
  private final String cellSeparator;

  public Table(int leftMarginLen, String cellSeparator) {
    this.leftMarginLen = leftMarginLen;
    this.cellSeparator = cellSeparator;
  }

  public void addRow() {
    rows.add(new TableRow(cellSeparator));
  }

  public void addCell(TableCell cell) {
    if (rows.isEmpty()) {
      addRow();
    }
    rows.get(rows.size() - 1).columns.add(cell);
  }

  private void setRowHeight() {
    for (TableRow row : rows) {
      int maxRowHeight = 0;
      for (TableCell cell : row.columns) {
        maxRowHeight = Math.max(maxRowHeight, cell.normalizeBlocks());
      }
      for (TableCell cell : row.columns) {
        cell.setHeight(maxRowHeight);
      }
    }
  }

  private void setColumnWidth() {
    int maxColumns = 0;
    for (TableRow row : rows) {
      maxColumns = Math.max(maxColumns, row.columns.size());
    }
    for (int col = 0; col < maxColumns; col++) {
      int maxColumnWidth = 0;
      for (TableRow row : rows) {
        if (row.columns.size() > col) {
          maxColumnWidth = Math.max(maxColumnWidth, row.columns.get(col).width());
        }
      }
      for (TableRow row : rows) {
        if (row.columns.size() > col) {
          row.columns.get(col).setWidth(maxColumnWidth);
        }
      }
    }
  }

  public String getText() {
    if (rows.isEmpty()) {
      return "\n";
    }
    setRowHeight();
    setColumnWidth();
    List<String> out = new ArrayList<>();
    for (TableRow row : rows) {
      out.add(row.getText());
    }
    return String.join("\n", out) + "\n";
  }

  public int leftMarginLen() {
    return leftMarginLen;
  }
}
