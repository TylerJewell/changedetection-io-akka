package io.akka.changedetection.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Reading a spreadsheet the way the two import routes need to read one.
 *
 * <p>The workbooks here are written by hand rather than by the writer this project also has,
 * because the files an operator uploads were written by somebody else's tool: cells that carry
 * their value inline, cells that refer to a shared table, cells that carry a boolean, and rows
 * that skip a column altogether are all shapes a real export produces and none of them is what
 * this project's own writer emits.
 */
class XlsxReaderTest {

  private static byte[] workbook(String sheetXml, String sharedStringsXml) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
      zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      if (sharedStringsXml != null) {
        zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
        zip.write(sharedStringsXml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    return buffer.toByteArray();
  }

  @Test
  void columnLettersBecomeNumbersFromOne() {
    assertEquals(1, XlsxReader.columnNumber("A"));
    assertEquals(26, XlsxReader.columnNumber("Z"));
    assertEquals(27, XlsxReader.columnNumber("AA"));
    assertEquals(52, XlsxReader.columnNumber("AZ"));
    assertEquals(702, XlsxReader.columnNumber("ZZ"));
  }

  @Test
  void aCellCarryingItsOwnTextIsRead() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"inlineStr\"><is><t>url</t></is></c>"
                + "<c r=\"B1\" t=\"inlineStr\"><is><t>name</t></is></c>"
                + "</row></sheetData></worksheet>",
            null);
    List<XlsxReader.Row> rows = XlsxReader.rows(file);
    assertEquals(1, rows.size());
    assertEquals("url", rows.get(0).cells().get(1));
    assertEquals("name", rows.get(0).cells().get(2));
  }

  @Test
  void aCellReferringToTheSharedTableIsResolved() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"s\"><v>0</v></c>"
                + "<c r=\"B1\" t=\"s\"><v>1</v></c>"
                + "</row></sheetData></worksheet>",
            "<sst><si><t>https://example.com</t></si><si><t>A title</t></si></sst>");
    List<XlsxReader.Row> rows = XlsxReader.rows(file);
    assertEquals("https://example.com", rows.get(0).cells().get(1));
    assertEquals("A title", rows.get(0).cells().get(2));
  }

  @Test
  void aSharedEntrySplitAcrossRunsIsJoined() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                + "</sheetData></worksheet>",
            "<sst><si><r><t>https://</t></r><r><t>example.com</t></r></si></sst>");
    assertEquals("https://example.com", XlsxReader.rows(file).get(0).cells().get(1));
  }

  @Test
  void aBooleanIsReadAsAWordRatherThanADigit() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"b\"><v>1</v></c><c r=\"B1\" t=\"b\"><v>0</v></c>"
                + "</row></sheetData></worksheet>",
            null);
    assertEquals("TRUE", XlsxReader.rows(file).get(0).cells().get(1));
    assertEquals("FALSE", XlsxReader.rows(file).get(0).cells().get(2));
  }

  @Test
  void aNumberIsReadAsItIsWritten() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>180</v></c></row>"
                + "</sheetData></worksheet>",
            null);
    assertEquals("180", XlsxReader.rows(file).get(0).cells().get(1));
  }

  @Test
  void aRowThatSkipsAColumnKeepsTheOthersWhereTheyAre() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"2\">"
                + "<c r=\"A2\" t=\"inlineStr\"><is><t>first</t></is></c>"
                + "<c r=\"C2\" t=\"inlineStr\"><is><t>third</t></is></c>"
                + "</row></sheetData></worksheet>",
            null);
    XlsxReader.Row row = XlsxReader.rows(file).get(0);
    assertEquals(2, row.number());
    assertEquals("first", row.cells().get(1));
    assertEquals("third", row.cells().get(3));
    assertTrue(row.cells().get(2) == null, "the empty cell is absent rather than blank");
  }

  @Test
  void anEmptyCellIsLeftOutRatherThanStoredAsNothing() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"inlineStr\"><is><t></t></is></c>"
                + "<c r=\"B1\"/>"
                + "</row></sheetData></worksheet>",
            null);
    assertEquals(0, XlsxReader.rows(file).get(0).cells().size());
  }

  @Test
  void markupInsideACellIsUnescaped() {
    byte[] file =
        workbook(
            "<worksheet><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"inlineStr\"><is><t>a &amp; b &lt;c&gt;</t></is></c>"
                + "</row></sheetData></worksheet>",
            null);
    assertEquals("a & b <c>", XlsxReader.rows(file).get(0).cells().get(1));
  }

  @Test
  void somethingThatIsNotASpreadsheetIsRefusedRatherThanReadAsEmpty() {
    assertThrows(
        IllegalArgumentException.class,
        () -> XlsxReader.rows("not a zip at all".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aWorkbookWithNoSheetReadsAsNoRows() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      zip.putNextEntry(new ZipEntry("docProps/app.xml"));
      zip.write("<Properties/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    assertEquals(0, XlsxReader.rows(buffer.toByteArray()).size());
  }

  @Test
  void minutesBecomeTheUnitsAnIntervalIsStoredIn() {
    assertEquals(
        java.util.Map.of("weeks", 0, "days", 0, "hours", 3, "minutes", 0, "seconds", 0),
        ImportEndpoint.intervalOf(180));
    assertEquals(
        java.util.Map.of("weeks", 1, "days", 1, "hours", 1, "minutes", 1, "seconds", 0),
        ImportEndpoint.intervalOf(7 * 24 * 60 + 24 * 60 + 60 + 1));
    assertEquals(
        java.util.Map.of("weeks", 0, "days", 0, "hours", 0, "minutes", 0, "seconds", 0),
        ImportEndpoint.intervalOf(0));
  }
}
