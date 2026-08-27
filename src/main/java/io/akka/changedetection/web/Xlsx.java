package io.akka.changedetection.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A spreadsheet, written by hand.
 *
 * <p>A spreadsheet rather than a comma-separated file because the point of the export is that a
 * price stays a number and a moment stays a date, whatever decimal separator the reader's
 * machine uses -- which a comma-separated file cannot promise. Written directly rather than
 * through a library because what is needed is four columns and two number formats, and a
 * general-purpose spreadsheet library is a large dependency for that.
 */
public final class Xlsx {

  /** The value of one cell: a moment, a number, or words. */
  public sealed interface Cell permits Moment, Number, Words {}

  public record Moment(long epochSeconds) implements Cell {}

  public record Number(Double value) implements Cell {}

  public record Words(String value) implements Cell {}

  private final String sheetName;
  private final List<String> headings = new ArrayList<>();
  private final List<List<Cell>> rows = new ArrayList<>();
  private final List<Integer> columnWidths = new ArrayList<>();

  public Xlsx(String sheetName) {
    this.sheetName = sheetName;
  }

  public Xlsx heading(List<String> names) {
    headings.clear();
    headings.addAll(names);
    return this;
  }

  public Xlsx widths(List<Integer> widths) {
    columnWidths.clear();
    columnWidths.addAll(widths);
    return this;
  }

  public Xlsx row(List<Cell> cells) {
    rows.add(new ArrayList<>(cells));
    return this;
  }

  public byte[] bytes() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream archive = new ZipOutputStream(buffer)) {
      write(archive, "[Content_Types].xml", contentTypes());
      write(archive, "_rels/.rels", relationships());
      write(archive, "xl/workbook.xml", workbook());
      write(archive, "xl/_rels/workbook.xml.rels", workbookRelationships());
      write(archive, "xl/styles.xml", styles());
      write(archive, "xl/worksheets/sheet1.xml", sheet());
    } catch (IOException e) {
      throw new IllegalStateException("could not build the spreadsheet", e);
    }
    return buffer.toByteArray();
  }

  private static void write(ZipOutputStream archive, String name, String contents)
      throws IOException {
    archive.putNextEntry(new ZipEntry(name));
    archive.write(contents.getBytes(StandardCharsets.UTF_8));
    archive.closeEntry();
  }

  private static String contentTypes() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\""
        + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd."
        + "openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd."
        + "openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd."
        + "openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
        + "</Types>";
  }

  private static String relationships() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
        + "2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
        + "</Relationships>";
  }

  private String workbook() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
        + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        + "<sheets><sheet name=\"" + escape(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
        + "</workbook>";
  }

  private static String workbookRelationships() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
        + "2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
        + "2006/relationships/styles\" Target=\"styles.xml\"/>"
        + "</Relationships>";
  }

  /** Two formats: the plain one, and the one that shows a moment as a date and a time. */
  private static String styles() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
        + "<numFmts count=\"1\">"
        + "<numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm:ss\"/>"
        + "</numFmts>"
        + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
        + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
        + "<borders count=\"1\"><border/></borders>"
        + "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
        + "<cellXfs count=\"2\">"
        + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
        + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>"
        + "</cellXfs>"
        + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
        + "</styleSheet>";
  }

  private String sheet() {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
    if (!columnWidths.isEmpty()) {
      sb.append("<cols>");
      for (int index = 0; index < columnWidths.size(); index++) {
        sb.append("<col min=\"")
            .append(index + 1)
            .append("\" max=\"")
            .append(index + 1)
            .append("\" width=\"")
            .append(columnWidths.get(index))
            .append("\" customWidth=\"1\"/>");
      }
      sb.append("</cols>");
    }
    sb.append("<sheetData>");

    int rowNumber = 1;
    if (!headings.isEmpty()) {
      sb.append("<row r=\"").append(rowNumber).append("\">");
      for (int column = 0; column < headings.size(); column++) {
        sb.append("<c r=\"")
            .append(reference(column, rowNumber))
            .append("\" t=\"inlineStr\"><is><t>")
            .append(escape(headings.get(column)))
            .append("</t></is></c>");
      }
      sb.append("</row>");
      rowNumber++;
    }

    for (List<Cell> row : rows) {
      sb.append("<row r=\"").append(rowNumber).append("\">");
      for (int column = 0; column < row.size(); column++) {
        Cell cell = row.get(column);
        String reference = reference(column, rowNumber);
        if (cell instanceof Moment moment) {
          sb.append("<c r=\"")
              .append(reference)
              .append("\" s=\"1\"><v>")
              .append(serialDate(moment.epochSeconds()))
              .append("</v></c>");
        } else if (cell instanceof Number number) {
          if (number.value() == null) {
            continue;
          }
          sb.append("<c r=\"")
              .append(reference)
              .append("\"><v>")
              .append(trim(number.value()))
              .append("</v></c>");
        } else if (cell instanceof Words words) {
          if (words.value() == null || words.value().isEmpty()) {
            continue;
          }
          sb.append("<c r=\"")
              .append(reference)
              .append("\" t=\"inlineStr\"><is><t>")
              .append(escape(words.value()))
              .append("</t></is></c>");
        }
      }
      sb.append("</row>");
      rowNumber++;
    }

    return sb.append("</sheetData></worksheet>").toString();
  }

  /**
   * A moment as a spreadsheet counts one.
   *
   * <p>Days since the last day of 1899, because that is the epoch spreadsheets agreed on --
   * including its famous off-by-one about 1900 being a leap year, which is why the base is the
   * thirtieth of December rather than the thirty-first.
   */
  static String serialDate(long epochSeconds) {
    LocalDateTime when =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    LocalDateTime epoch = LocalDateTime.of(1899, 12, 30, 0, 0);
    long seconds = ChronoUnit.SECONDS.between(epoch, when);
    return trim(seconds / 86400.0);
  }

  static String trim(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
      return String.valueOf((long) value);
    }
    return String.valueOf(value);
  }

  static String reference(int column, int row) {
    StringBuilder name = new StringBuilder();
    int index = column;
    do {
      name.insert(0, (char) ('A' + index % 26));
      index = index / 26 - 1;
    } while (index >= 0);
    return name + String.valueOf(row);
  }

  static String escape(String value) {
    StringBuilder sb = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        case '\'' -> sb.append("&apos;");
        default -> {
          // A control character is not allowed in the format at all; dropping it keeps the
          // file openable rather than producing one a spreadsheet refuses.
          if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
