package io.akka.changedetection.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Tag;

/**
 * The element the original hands to its renderer and to an XPath expression.
 *
 * <p>A whole page is handed over as the whole page. A fragment is not: it arrives wrapped, and
 * which wrapper it gets is decided by what is in it. That wrapper is not cosmetic -- a
 * {@code div} indents its contents by two characters in the extracted text, and it is also an
 * element an XPath expression can match, so {@code //div} against a fragment of two paragraphs
 * selects the whole fragment on one side and nothing at all on the other.
 */
public final class LxmlTree {

  private static final Pattern LOOKS_LIKE_FULL_HTML =
      Pattern.compile("^\\s*<(?:html|!doctype)", Pattern.CASE_INSENSITIVE);
  private static final Pattern XML_DECLARATION = Pattern.compile("^<\\?xml [^>]+?\\?>");

  private static final Set<String> BLOCK_TAGS =
      Set.of(
          "address", "blockquote", "caption", "center", "col", "colgroup", "dd", "del", "dir",
          "div", "dl", "dt", "fieldset", "form", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "ins",
          "isindex", "legend", "li", "menu", "noscript", "ol", "optgroup", "option", "p", "pre",
          "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul");

  private LxmlTree() {}

  /** The whole document, as the tree-level entry point produces it. */
  public static Element document(String html) {
    return LxmlParser.parseDocument(stripXmlDeclaration(html));
  }

  /** The fragment-aware entry point: a document, a single element, or a wrapper around many. */
  public static Element fromString(String html) {
    if (html == null) {
      return null;
    }
    String content = stripXmlDeclaration(html);
    if (content.strip().isEmpty()) {
      return null;
    }

    boolean isFullHtml = LOOKS_LIKE_FULL_HTML.matcher(content).find();
    Element root = LxmlParser.parseDocument(content);
    if (isFullHtml) {
      return root;
    }

    Element head = LxmlParser.headOf(root);
    Element body = LxmlParser.bodyOf(root);

    if (head != null && !head.childNodes().isEmpty()) {
      return root;
    }
    if (body == null) {
      return root;
    }

    List<Element> children = body.children();
    if (children.size() == 1
        && isBlank(textBeforeFirstElement(body))
        && isBlank(textAfterLastElement(body))) {
      return children.get(0);
    }

    Document owner = new Document("");
    owner.outputSettings().prettyPrint(false);
    Element wrapper =
        new Element(Tag.valueOf(containsBlockLevelTag(body) ? "div" : "span"), "");
    owner.appendChild(wrapper);
    for (Node node : new ArrayList<>(body.childNodes())) {
      node.remove();
      wrapper.appendChild(node);
    }
    return wrapper;
  }

  private static String stripXmlDeclaration(String html) {
    // Only the declaration goes; the surrounding whitespace is content, and a caller that
    // wants it gone strips it before calling.
    if (html.stripLeading().startsWith("<?xml ")) {
      return XML_DECLARATION.matcher(html.stripLeading()).replaceFirst("");
    }
    return html;
  }

  private static boolean containsBlockLevelTag(Element body) {
    for (Element element : body.getAllElements()) {
      if (element != body && BLOCK_TAGS.contains(element.normalName())) {
        return true;
      }
    }
    return false;
  }

  private static String textBeforeFirstElement(Element body) {
    StringBuilder sb = new StringBuilder();
    for (Node node : body.childNodes()) {
      if (node instanceof Element) {
        break;
      }
      if (node instanceof TextNode textNode) {
        sb.append(textNode.getWholeText());
      } else if (node instanceof DataNode dataNode) {
        sb.append(dataNode.getWholeData());
      }
    }
    return sb.toString();
  }

  private static String textAfterLastElement(Element body) {
    List<Node> nodes = body.childNodes();
    StringBuilder sb = new StringBuilder();
    for (int i = nodes.size() - 1; i >= 0; i--) {
      Node node = nodes.get(i);
      if (node instanceof Element) {
        break;
      }
      if (node instanceof TextNode textNode) {
        sb.insert(0, textNode.getWholeText());
      } else if (node instanceof DataNode dataNode) {
        sb.insert(0, dataNode.getWholeData());
      }
    }
    return sb.toString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.strip().isEmpty();
  }

  /** Markup written the way the original's tree writer writes HTML: no indent, no declaration. */
  public static String serialise(Node node) {
    StringBuilder sb = new StringBuilder();
    write(node, sb);
    return sb.toString();
  }

  private static void write(Node node, StringBuilder sb) {
    if (node instanceof TextNode textNode) {
      sb.append(SoupSerializer.escapeText(textNode.getWholeText()));
      return;
    }
    if (node instanceof DataNode dataNode) {
      sb.append(dataNode.getWholeData());
      return;
    }
    if (node instanceof Comment comment) {
      sb.append("<!--").append(comment.getData()).append("-->");
      return;
    }
    if (!(node instanceof Element element)) {
      return;
    }
    String name = element.normalName();
    sb.append('<').append(name);
    for (org.jsoup.nodes.Attribute attribute : element.attributes()) {
      sb.append(' ').append(attribute.getKey());
      sb.append("=\"").append(escapeAttribute(attribute.getValue())).append('"');
    }
    sb.append('>');
    if (SoupParser.EMPTY_ELEMENTS.contains(name)) {
      return;
    }
    for (Node child : element.childNodes()) {
      write(child, sb);
    }
    sb.append("</").append(name).append('>');
  }

  private static String escapeAttribute(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
