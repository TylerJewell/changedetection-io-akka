package io.akka.changedetection.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Tag;

/**
 * The tree the original's rendering parser builds: a whole document, with the tags a page left
 * open closed on its behalf.
 *
 * <p>The rules are a fixed table -- a start tag names the open tags it closes -- rather than a
 * general content model, and reproducing the table is reproducing the tree. A page that writes
 * two paragraphs without closing the first is two sibling paragraphs here and would be two
 * nested ones under the selection parser, and the difference is one block margin in the
 * extracted text.
 *
 * <p>Two further behaviours of that parser are part of the tree and not of the markup:
 * character data at the top level opens an implied paragraph, and {@code html}, {@code head}
 * and {@code body} are supplied whether or not the page wrote them.
 */
public final class LxmlParser {

  private static final Set<String> HEAD_ELEMENTS =
      Set.of("head", "title", "base", "link", "meta", "style", "script", "noscript");

  private static final String[] HEADINGS = {"h1", "h2", "h3", "h4", "h5", "h6"};

  /** Tags a start tag closes, keyed by the start tag. */
  private static final Map<String, Set<String>> CLOSES = buildCloseTable();

  private LxmlParser() {}

  /**
   * Text with the characters the tree cannot hold removed.
   *
   * <p>The rendering parser is built on an XML tree, and the control characters below are not
   * legal characters there, so they never reach the extracted text. The selection parser is not
   * built on one and keeps them. A page carrying a form feed therefore has one fewer separator
   * in the rendered text than in the selected markup, which is a real difference and not a
   * tidy-up: keeping the character would put a space where the original has nothing.
   */
  private static String dropCharactersTheTreeCannotHold(String text) {
    StringBuilder sb = null;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean legal = c == '\t' || c == '\n' || c == '\r' || c >= 0x20;
      if (legal) {
        if (sb != null) {
          sb.append(c);
        }
      } else if (sb == null) {
        sb = new StringBuilder(text.length());
        sb.append(text, 0, i);
      }
    }
    return sb == null ? text : sb.toString();
  }

  private static Map<String, Set<String>> buildCloseTable() {
    Map<String, Set<String>> table = new HashMap<>();
    put(table, "form", "form", "p", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "dl", "ul", "ol",
        "menu", "dir", "address", "pre", "listing", "xmp");
    put(table, "head", "p");
    put(table, "title", "p");
    put(table, "body", "head", "style", "link", "meta", "script", "title", "p");
    put(table, "li", "p", "h1", "h2", "h3", "h4", "h5", "h6", "dl", "address", "pre", "listing",
        "xmp", "head", "li");
    put(table, "hr", "p", "head");
    for (String heading : HEADINGS) {
      put(table, heading, "p", "head");
    }
    put(table, "dir", "p", "head");
    put(table, "address", "p", "head", "ul");
    put(table, "pre", "p", "head", "ul");
    put(table, "listing", "p", "head");
    put(table, "xmp", "p", "head");
    put(table, "blockquote", "p", "head");
    put(table, "dl", "p", "dt", "menu", "dir", "address", "pre", "listing", "xmp", "head");
    put(table, "dt", "p", "menu", "dir", "address", "pre", "listing", "xmp", "head", "dd");
    put(table, "dd", "p", "menu", "dir", "address", "pre", "listing", "xmp", "head", "dt");
    put(table, "ul", "p", "head", "ol", "menu", "dir", "address", "pre", "listing", "xmp");
    put(table, "ol", "p", "head", "ul");
    put(table, "menu", "p", "head", "ul");
    put(table, "p", "p", "head", "h1", "h2", "h3", "h4", "h5", "h6");
    put(table, "div", "p", "head");
    put(table, "noscript", "head");
    put(table, "center", "font", "b", "i", "p", "head");
    put(table, "a", "a", "head");
    put(table, "caption", "p");
    put(table, "colgroup", "caption", "legend", "tr", "col", "p");
    put(table, "col", "caption", "legend", "tr", "p");
    put(table, "table", "p", "head", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "listing", "xmp",
        "a");
    put(table, "th", "th", "td", "p", "span", "font", "a", "b", "i", "u");
    put(table, "td", "th", "td", "p", "span", "font", "a", "b", "i", "u");
    put(table, "tr", "th", "td", "tr", "caption", "col", "colgroup", "p", "span", "font", "a",
        "b", "i", "u");
    put(table, "thead", "caption", "col", "colgroup");
    put(table, "tfoot", "th", "td", "tr", "caption", "col", "colgroup", "thead", "tbody", "p",
        "span", "font", "a", "b", "i", "u");
    put(table, "tbody", "th", "td", "tr", "caption", "col", "colgroup", "thead", "tfoot",
        "tbody", "p", "span", "font", "a", "b", "i", "u");
    put(table, "optgroup", "option");
    put(table, "option", "option");
    put(table, "fieldset", "legend", "p", "head", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
        "listing", "xmp", "a");
    return table;
  }

  private static void put(Map<String, Set<String>> table, String tag, String... closes) {
    table.put(tag, Set.of(closes));
  }

  /** The parsed document's {@code html} element. */
  public static Element parseDocument(String html) {
    Document owner = new Document("");
    owner.outputSettings().prettyPrint(false);
    Element root = new Element(Tag.valueOf("html"), "");
    owner.appendChild(root);
    Element head = new Element(Tag.valueOf("head"), "");
    Element body = new Element(Tag.valueOf("body"), "");
    root.appendChild(head);
    root.appendChild(body);

    List<Element> stack = new ArrayList<>();
    stack.add(body);
    boolean inHead = true;
    boolean bodyStarted = false;

    for (Tokenizer.Token token : Tokenizer.tokenize(html)) {
      if (token instanceof Tokenizer.StartTag start) {
        String name = start.name();
        if (name.equals("html")) {
          for (Map.Entry<String, String> attribute : start.attributes().entrySet()) {
            root.attr(attribute.getKey(), attribute.getValue() == null ? "" : attribute.getValue());
          }
          continue;
        }
        if (name.equals("head")) {
          inHead = true;
          continue;
        }
        if (name.equals("body")) {
          inHead = false;
          bodyStarted = true;
          for (Map.Entry<String, String> attribute : start.attributes().entrySet()) {
            body.attr(attribute.getKey(), attribute.getValue() == null ? "" : attribute.getValue());
          }
          while (stack.size() > 1) {
            stack.remove(stack.size() - 1);
          }
          continue;
        }
        if (inHead && !bodyStarted && HEAD_ELEMENTS.contains(name) && stack.size() == 1) {
          Element element = element(start);
          head.appendChild(element);
          if (!start.selfClosing() && !SoupParser.EMPTY_ELEMENTS.contains(name)) {
            stack.add(element);
          }
          continue;
        }
        inHead = false;
        autoClose(stack, name);
        Element parent = stack.get(stack.size() - 1);
        Element element = element(start);
        parent.appendChild(element);
        if (!start.selfClosing() && !SoupParser.EMPTY_ELEMENTS.contains(name)) {
          stack.add(element);
        }
      } else if (token instanceof Tokenizer.EndTag end) {
        String name = end.name();
        if (name.equals("html") || name.equals("body") || name.equals("head")) {
          continue;
        }
        for (int i = stack.size() - 1; i >= 1; i--) {
          if (stack.get(i).normalName().equals(name)) {
            while (stack.size() > i) {
              stack.remove(stack.size() - 1);
            }
            break;
          }
        }
      } else if (token instanceof Tokenizer.Text rawText) {
        Tokenizer.Text text = new Tokenizer.Text(dropCharactersTheTreeCannotHold(rawText.content()));
        if (text.content().isEmpty()) {
          continue;
        }
        Element current = stack.get(stack.size() - 1);
        if (Tokenizer.RAW_TEXT_ELEMENTS.contains(current.normalName())) {
          current.appendChild(new DataNode(text.content()));
          continue;
        }
        // Only an implied body gets an implied paragraph. Where the page wrote its own
        // <body>, text directly inside it stays directly inside it -- and a paragraph carries a
        // bottom margin, so inventing one adds a blank line to the end of every such page.
        if (current == body && !bodyStarted && !text.content().isBlank()
            && body.childNodeSize() == 0) {
          inHead = false;
          Element implied = impliedParagraph(body);
          // Whitespace ahead of the first content in a document is ignorable, and the parser
          // drops it rather than opening the paragraph with it.
          implied.appendChild(new TextNode(text.content().stripLeading()));
          stack.add(implied);
          continue;
        }
        if (current == body && text.content().isBlank() && !bodyStarted) {
          continue;
        }
        current.appendChild(new TextNode(text.content()));
      } else if (token instanceof Tokenizer.CommentToken comment) {
        stack.get(stack.size() - 1).appendChild(new Comment(comment.content()));
      }
    }

    if (head.childNodes().isEmpty()) {
      head.remove();
    }
    return root;
  }

  /**
   * The implied paragraph the parser opens for text at the top level. It opens once: after a
   * block element has closed, further text at that level stays where it is.
   */
  private static Element impliedParagraph(Element body) {
    Element paragraph = new Element(Tag.valueOf("p"), "");
    body.appendChild(paragraph);
    return paragraph;
  }

  /**
   * The open tags a start tag closes, taken off the top only.
   *
   * <p>It stops at the first open tag the table does not name, rather than searching down for
   * one it does. A list item opened inside a nested list would otherwise close the item that
   * contains the whole nested list, flattening one level of indentation out of every nested
   * list on every page.
   */
  private static void autoClose(List<Element> stack, String startTag) {
    Set<String> closes = CLOSES.get(startTag);
    if (closes == null) {
      return;
    }
    while (stack.size() > 1 && closes.contains(stack.get(stack.size() - 1).normalName())) {
      stack.remove(stack.size() - 1);
    }
  }

  private static Element element(Tokenizer.StartTag start) {
    Element element = new Element(Tag.valueOf(start.name()), "");
    for (Map.Entry<String, String> attribute : start.attributes().entrySet()) {
      element.attr(attribute.getKey(), attribute.getValue() == null ? "" : attribute.getValue());
    }
    return element;
  }

  /**
   * The element the fragment-aware entry point returns: the whole document when the markup
   * looked like one, and otherwise the single element it held, or a wrapper around what it held
   * -- a {@code div} when a block-level tag appears anywhere inside and a {@code span} when
   * none does.
   */
  public static Element parseFragment(String html) {
    Element root = parseDocument(html);
    return root;
  }

  static Element bodyOf(Element htmlElement) {
    for (Node node : htmlElement.childNodes()) {
      if (node instanceof Element element && element.normalName().equals("body")) {
        return element;
      }
    }
    return null;
  }

  static Element headOf(Element htmlElement) {
    for (Node node : htmlElement.childNodes()) {
      if (node instanceof Element element && element.normalName().equals("head")) {
        return element;
      }
    }
    return null;
  }

  static boolean isHeadElement(String name) {
    return HEAD_ELEMENTS.contains(name.toLowerCase(Locale.ROOT));
  }
}
