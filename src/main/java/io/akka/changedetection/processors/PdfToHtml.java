package io.akka.changedetection.processors;

import io.akka.changedetection.text.PythonText;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A document turned into markup so its text can be compared.
 *
 * <p>Done by an outside tool, as the original does it, because reading a document format
 * faithfully is a large piece of work of its own and the deployments that watch documents
 * already have the tool. Where the tool is absent the watch says so plainly, rather than
 * reporting no change on a document nobody can read.
 *
 * <p>A line naming the document's own checksum and size is appended, because two documents can
 * produce identical text and differ -- a scanned page re-scanned, for instance -- and the
 * comparison would otherwise call them the same.
 */
public final class PdfToHtml {

  private PdfToHtml() {}

  public static String convert(byte[] rawContent) {
    String tool = System.getenv("PDF_TO_HTML_TOOL");
    if (tool == null || tool.isBlank()) {
      tool = "pdftohtml";
    }
    ProcessBuilder builder =
        new ProcessBuilder(List.of(tool, "-stdout", "-", "-s", "out.pdf", "-i"));
    builder.redirectErrorStream(false);
    Process process;
    try {
      process = builder.start();
    } catch (Exception e) {
      throw new ProcessorExceptions.PdfToHtmlToolNotFound(
          "Command-line `" + tool + "` tool was not found in system PATH, was it installed?");
    }
    try {
      try (OutputStream input = process.getOutputStream()) {
        input.write(rawContent);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream output = process.getInputStream()) {
        output.transferTo(out);
      }
      process.waitFor();
      String html = out.toString(StandardCharsets.UTF_8);
      String metadata =
          "<p>Added by changedetection.io: Document checksum - "
              + PythonText.md5Hex(rawContent).toUpperCase(java.util.Locale.ROOT)
              + " Original file size - "
              + rawContent.length
              + " bytes</p>";
      return html.replace("</body>", metadata + "</body>");
    } catch (Exception e) {
      process.destroyForcibly();
      throw new ProcessorExceptions.PdfToHtmlToolNotFound(
          "The document converter failed: " + e.getMessage());
    }
  }
}
