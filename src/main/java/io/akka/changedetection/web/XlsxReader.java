package io.akka.changedetection.web;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reading a spreadsheet, for the two import routes that take one.
 *
 * <p>A spreadsheet is a zip of XML documents, and only two of them matter: the first sheet's
 * cells, and the table of strings the cells refer to by number rather than carrying inline.
 * Nothing here writes a spreadsheet -- that is {@link Xlsx}, and the two do not share a shape
 * because reading has to tolerate whatever wrote the file and writing does not.
 */
public final class XlsxReader {

  private static final Pattern ROW = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL);

  private static final Pattern CELL =
      Pattern.compile("<c\\s+([^>]*?)/>|<c\\s+([^>]*?)>(.*?)</c>", Pattern.DOTALL);

  private static final Pattern VALUE = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);

  private static final Pattern INLINE = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);

  private static final Pattern SHARED_STRING =
      Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL);

  private static final Pattern REFERENCE = Pattern.compile("r=\"([A-Z]+)([0-9]+)\"");

  private static final Pattern TYPE = Pattern.compile("t=\"([^\"]+)\"");

  private XlsxReader() {}

  /** One row, its cells keyed by the column number they sit in, counting from one. */
  public record Row(int number, Map<Integer, String> cells) {}

  /** Every row of the first sheet, in the order the file holds them. */
  public static List<Row> rows(byte[] workbook) {
    Map<String, byte[]> parts = unzip(workbook);
    if (parts.isEmpty()) {
      // Nothing unpacked at all: this is not a spreadsheet, and reading it as an empty one
      // would report an import that found no rows rather than a file that could not be read.
      throw new IllegalArgumentException("not a readable spreadsheet");
    }
    List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
    byte[] sheet = firstSheet(parts);
    if (sheet == null) {
      return new ArrayList<>();
    }
    String xml = new String(sheet, StandardCharsets.UTF_8);

    List<Row> out = new ArrayList<>();
    Matcher rows = ROW.matcher(xml);
    int fallbackNumber = 0;
    while (rows.find()) {
      fallbackNumber++;
      String body = rows.group(1);
      Map<Integer, String> cells = new LinkedHashMap<>();
      int rowNumber = fallbackNumber;
      Matcher cellMatcher = CELL.matcher(body);
      int fallbackColumn = 0;
      while (cellMatcher.find()) {
        fallbackColumn++;
        String attributes =
            cellMatcher.group(1) != null ? cellMatcher.group(1) : cellMatcher.group(2);
        String content = cellMatcher.group(3) == null ? "" : cellMatcher.group(3);

        int column = fallbackColumn;
        Matcher reference = REFERENCE.matcher(attributes);
        if (reference.find()) {
          column = columnNumber(reference.group(1));
          rowNumber = Integer.parseInt(reference.group(2));
          fallbackColumn = column;
        }

        String type = "";
        Matcher typeMatcher = TYPE.matcher(attributes);
        if (typeMatcher.find()) {
          type = typeMatcher.group(1);
        }

        String value = null;
        if ("inlineStr".equals(type)) {
          Matcher inline = INLINE.matcher(content);
          StringBuilder joined = new StringBuilder();
          while (inline.find()) {
            joined.append(unescape(inline.group(1)));
          }
          value = joined.toString();
        } else {
          Matcher valueMatcher = VALUE.matcher(content);
          if (valueMatcher.find()) {
            String raw = unescape(valueMatcher.group(1));
            if ("s".equals(type)) {
              try {
                int index = Integer.parseInt(raw.strip());
                value = index >= 0 && index < shared.size() ? shared.get(index) : "";
              } catch (NumberFormatException e) {
                value = raw;
              }
            } else if ("b".equals(type)) {
              value = "1".equals(raw.strip()) ? "TRUE" : "FALSE";
            } else {
              value = raw;
            }
          }
        }
        if (value != null && !value.isEmpty()) {
          cells.put(column, value);
        }
      }
      out.add(new Row(rowNumber, cells));
    }
    return out;
  }

  /** Column A is one, Z is twenty-six, AA is twenty-seven. */
  static int columnNumber(String letters) {
    int number = 0;
    for (int index = 0; index < letters.length(); index++) {
      number = number * 26 + (letters.charAt(index) - 'A' + 1);
    }
    return number;
  }

  private static byte[] firstSheet(Map<String, byte[]> parts) {
    byte[] named = parts.get("xl/worksheets/sheet1.xml");
    if (named != null) {
      return named;
    }
    for (Map.Entry<String, byte[]> entry : parts.entrySet()) {
      if (entry.getKey().startsWith("xl/worksheets/") && entry.getKey().endsWith(".xml")) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static List<String> sharedStrings(byte[] part) {
    List<String> out = new ArrayList<>();
    if (part == null) {
      return out;
    }
    String xml = new String(part, StandardCharsets.UTF_8);
    Matcher entries = SHARED_STRING.matcher(xml);
    while (entries.find()) {
      // One entry can hold several runs where the cell mixed formatting; the value is all of
      // them joined, which is what the cell shows.
      Matcher runs = INLINE.matcher(entries.group(1));
      StringBuilder joined = new StringBuilder();
      while (runs.find()) {
        joined.append(unescape(runs.group(1)));
      }
      out.add(joined.toString());
    }
    return out;
  }

  private static Map<String, byte[]> unzip(byte[] workbook) {
    Map<String, byte[]> out = new LinkedHashMap<>();
    try (InputStream in = new ByteArrayInputStream(workbook);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (!entry.isDirectory()) {
          out.put(entry.getName().replace('\\', '/'), zip.readAllBytes());
        }
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("not a readable spreadsheet", e);
    }
    return out;
  }

  private static String unescape(String value) {
    return value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
  }
}
