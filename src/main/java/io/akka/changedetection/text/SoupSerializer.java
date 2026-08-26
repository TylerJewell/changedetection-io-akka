package io.akka.changedetection.text;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Markup written back out the way the original writes it.
 *
 * <p>This is not cosmetic. The output of a subtractive selector is the document the next stage
 * reads, and the output of an include filter is what gets turned into the compared text. So
 * the attribute order, the spelling of an empty element and which characters are escaped are
 * all inputs to the checksum, and two serialisers that both produce valid markup produce two
 * different verdicts.
 */
public final class SoupSerializer {

  private SoupSerializer() {}

  /** The children of the given element, serialised; the element itself is not written. */
  public static String innerHtml(Element element) {
    StringBuilder sb = new StringBuilder();
    for (Node child : element.childNodes()) {
      write(child, sb);
    }
    return sb.toString();
  }

  public static String outerHtml(Node node) {
    StringBuilder sb = new StringBuilder();
    write(node, sb);
    return sb.toString();
  }

  private static void write(Node node, StringBuilder sb) {
    if (node instanceof TextNode textNode) {
      sb.append(escapeText(textNode.getWholeText()));
      return;
    }
    if (node instanceof DataNode dataNode) {
      sb.append(dataNode.getWholeData());
      return;
    }
    if (node instanceof Comment comment) {
      String data = comment.getData();
      if (data.startsWith("?")) {
        sb.append('<').append(data).append('>');
      } else {
        sb.append("<!--").append(data).append("-->");
      }
      return;
    }
    if (node instanceof DocumentType docType) {
      sb.append("<!DOCTYPE ").append(docType.attr("name")).append(">\n");
      return;
    }
    if (!(node instanceof Element element)) {
      return;
    }

    String name = element.normalName();
    sb.append('<').append(name);
    List<Attribute> attributes = new ArrayList<>(element.attributes().asList());
    attributes.sort((a, b) -> a.getKey().compareTo(b.getKey()));
    for (Attribute attribute : attributes) {
      sb.append(' ').append(attribute.getKey()).append("=\"")
          .append(escapeAttribute(attribute.getValue()))
          .append('"');
    }
    if (SoupParser.EMPTY_ELEMENTS.contains(name)) {
      sb.append("/>");
      return;
    }
    sb.append('>');
    for (Node child : element.childNodes()) {
      write(child, sb);
    }
    sb.append("</").append(name).append('>');
  }

  /** The minimal escape the original applies to text: ampersand and the two angle brackets. */
  public static String escapeText(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String escapeAttribute(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }
}
