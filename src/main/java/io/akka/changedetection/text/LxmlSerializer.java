package io.akka.changedetection.text;

import java.util.Locale;
import java.util.Set;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Markup written the way the original's tree writer writes it.
 *
 * <p>The writer's "pretty" mode is the reason this has to be exact rather than merely valid.
 * Every element an XPath filter matches is written with that mode on, and the newlines it
 * inserts are what separate one match from the next once the result is turned into text. Where
 * it puts them is a rule with several clauses -- an element with a single child gets none, an
 * inline element gets none, and a paragraph gets none whatever it contains -- and dropping any
 * clause merges or splits lines the original does not.
 *
 * <p>An element is also written together with the text that follows it, which is the tree
 * model's "tail". A filter matching a span in the middle of a sentence therefore returns the
 * span and the rest of the sentence, not the span alone.
 */
public final class LxmlSerializer {

  /** Elements the writer treats as inline, and so never breaks a line around. */
  private static final Set<String> INLINE =
      Set.of(
          "a", "abbr", "acronym", "applet", "b", "basefont", "bdo", "big", "br", "button",
          "cite", "code", "dfn", "em", "font", "i", "iframe", "img", "input", "kbd", "label",
          "map", "object", "q", "s", "samp", "script", "select", "small", "span", "strike",
          "strong", "style", "sub", "sup", "textarea", "tt", "u", "var");

  /** Elements written without an end tag. */
  private static final Set<String> VOID =
      Set.of(
          "area", "base", "basefont", "br", "col", "embed", "frame", "hr", "img", "input",
          "isindex", "link", "meta", "param", "source", "track", "wbr");

  private static final Set<String> RAW_TEXT = Set.of("script", "style");

  /**
   * The elements the writer knows about.
   *
   * <p>Its layout rules are all conditional on finding the element in its own table, so an
   * element the table does not list -- every tag added to the language after it was written, and
   * anything a page invents -- is written with no line breaks around or inside it at all. A
   * page built out of section, header, article and figure therefore comes back as one line
   * where a page built out of div comes back as many.
   */
  private static final Set<String> KNOWN =
      Set.of(
          "a", "abbr", "acronym", "address", "applet", "area", "b", "base", "basefont", "bdo",
          "big", "blockquote", "body", "br", "button", "caption", "center", "cite", "code",
          "col", "colgroup", "dd", "del", "dfn", "dir", "div", "dl", "dt", "em", "embed",
          "fieldset", "font", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6",
          "head", "hr", "html", "i", "iframe", "img", "input", "ins", "isindex", "kbd", "label",
          "legend", "li", "link", "map", "menu", "meta", "noframes", "noscript", "object", "ol",
          "optgroup", "option", "p", "param", "pre", "q", "s", "samp", "script", "select",
          "small", "span", "strike", "strong", "style", "sub", "sup", "table", "tbody", "td",
          "textarea", "tfoot", "th", "thead", "title", "tr", "tt", "u", "ul", "var");

  /** Elements whose end tag the language makes optional, and which the writer then omits. */
  private static final Set<String> OPTIONAL_END_TAG =
      Set.of(
          "colgroup", "dd", "dt", "li", "option", "p", "tbody", "td", "tfoot", "th", "thead",
          "tr", "head", "html");

  private LxmlSerializer() {}

  /** One matched node, with its tail and the trailing newline the pretty writer adds. */
  public static String tostringPretty(Node node) {
    StringBuilder sb = new StringBuilder();
    // The node being dumped is the root of this dump, so the rule about breaking between
    // siblings does not apply to it -- only to what is inside it.
    write(node, sb, true, true);
    writeTail(node, sb);
    sb.append('\n');
    return sb.toString();
  }

  /** A whole tree, written without the pretty writer's newlines. */
  public static String tostring(Node node) {
    StringBuilder sb = new StringBuilder();
    write(node, sb, false, true);
    return sb.toString();
  }

  private static void writeTail(Node node, StringBuilder sb) {
    Node sibling = node.getNextSibling();
    while (sibling != null
        && (sibling.getNodeType() == Node.TEXT_NODE
            || sibling.getNodeType() == Node.CDATA_SECTION_NODE)) {
      sb.append(escapeText(sibling.getNodeValue()));
      sibling = sibling.getNextSibling();
    }
  }

  private static void write(Node node, StringBuilder sb, boolean pretty, boolean isRoot) {
    switch (node.getNodeType()) {
      case Node.TEXT_NODE:
      case Node.CDATA_SECTION_NODE: {
        Node parent = node.getParentNode();
        String parentName =
            parent != null && parent.getNodeType() == Node.ELEMENT_NODE
                ? parent.getNodeName().toLowerCase(Locale.ROOT)
                : "";
        String value = node.getNodeValue() == null ? "" : node.getNodeValue();
        sb.append(RAW_TEXT.contains(parentName) ? value : escapeText(value));
        return;
      }
      case Node.COMMENT_NODE:
        sb.append("<!--").append(node.getNodeValue()).append("-->");
        return;
      case Node.ELEMENT_NODE:
        break;
      default:
        return;
    }

    String name = node.getNodeName().toLowerCase(Locale.ROOT);
    boolean known = KNOWN.contains(name);
    boolean inline = !known || INLINE.contains(name);
    boolean isParagraphLike = name.startsWith("p");

    sb.append('<').append(name);
    NamedNodeMap attributes = node.getAttributes();
    if (attributes != null) {
      for (int i = 0; i < attributes.getLength(); i++) {
        Node attribute = attributes.item(i);
        sb.append(' ').append(attribute.getNodeName());
        sb.append("=\"").append(escapeAttribute(attribute.getNodeValue())).append('"');
      }
    }
    sb.append('>');

    if (VOID.contains(name)) {
      if (!isRoot) {
        writeSiblingBreak(node, sb, pretty, inline);
      }
      return;
    }

    Node first = node.getFirstChild();
    Node last = node.getLastChild();
    boolean severalChildren = first != null && first != last;
    boolean breakInside = pretty && !inline && !isParagraphLike && severalChildren;

    if (breakInside && !isTextish(first)) {
      sb.append('\n');
    }
    for (Node child = first; child != null; child = child.getNextSibling()) {
      write(child, sb, pretty, false);
    }
    if (breakInside && !isTextish(last)) {
      sb.append('\n');
    }

    if (first == null && OPTIONAL_END_TAG.contains(name)) {
      // An element with no content whose end tag the language makes optional is written
      // without one. A list item written empty is "<li>", and the next item that follows it
      // in the extracted text lands on the same line rather than the next one.
      if (!isRoot) {
        writeSiblingBreak(node, sb, pretty, inline);
      }
      return;
    }

    sb.append("</").append(name).append('>');
    if (!isRoot) {
      writeSiblingBreak(node, sb, pretty, inline);
    }
  }

  private static void writeSiblingBreak(Node node, StringBuilder sb, boolean pretty, boolean inline) {
    if (!pretty || inline) {
      return;
    }
    Node next = node.getNextSibling();
    if (next == null || isTextish(next)) {
      return;
    }
    Node parent = node.getParentNode();
    if (parent == null || parent.getNodeType() != Node.ELEMENT_NODE) {
      return;
    }
    if (parent.getNodeName().toLowerCase(Locale.ROOT).startsWith("p")) {
      return;
    }
    sb.append('\n');
  }

  private static boolean isTextish(Node node) {
    return node != null
        && (node.getNodeType() == Node.TEXT_NODE
            || node.getNodeType() == Node.CDATA_SECTION_NODE
            || node.getNodeType() == Node.ENTITY_REFERENCE_NODE);
  }

  private static String escapeText(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeAttribute(String value) {
    return value.replace("&", "&amp;").replace("\"", "&quot;");
  }
}
