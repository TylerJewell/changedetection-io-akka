package io.akka.changedetection.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Tag;

/**
 * The tree the original's selection parser builds: nesting exactly as written.
 *
 * <p>It closes nothing on your behalf. Two paragraphs written without closing tags nest inside
 * one another rather than becoming siblings, a cell written outside a table stays a cell, and
 * no {@code html}, {@code head}, {@code body} or {@code tbody} is invented. Every one of those
 * is visible to a CSS selector, and a selector is how a watch says which part of a page it
 * cares about -- so a parser that tidies the markup up answers a different question than the
 * one the watch asked.
 */
public final class SoupParser {

  /** Elements that never take children, so they are closed as soon as they open. */
  public static final Set<String> EMPTY_ELEMENTS =
      Set.of(
          "area", "base", "br", "col", "embed", "hr", "img", "input", "keygen", "link",
          "menuitem", "meta", "param", "source", "track", "wbr", "basefont", "bgsound",
          "command", "frame", "isindex", "nextid", "spacer");

  private SoupParser() {}

  /** The document's top-level nodes, held under a root that is not itself part of the markup. */
  public static Element parse(String html) {
    Document owner = new Document("");
    owner.outputSettings().prettyPrint(false);
    Element root = new Element(Tag.valueOf("changedetection-fragment-root"), "");
    owner.appendChild(root);

    List<Element> stack = new ArrayList<>();
    stack.add(root);

    for (Tokenizer.Token token : Tokenizer.tokenize(html)) {
      Element current = stack.get(stack.size() - 1);
      if (token instanceof Tokenizer.Text text) {
        if (isRawTextElement(current)) {
          current.appendChild(new DataNode(text.content()));
        } else {
          current.appendChild(new TextNode(text.content()));
        }
      } else if (token instanceof Tokenizer.CommentToken comment) {
        current.appendChild(new Comment(comment.content()));
      } else if (token instanceof Tokenizer.Declaration declaration) {
        String content = declaration.content();
        if (content.toLowerCase(java.util.Locale.ROOT).startsWith("doctype")) {
          String rest = content.substring("doctype".length()).strip();
          current.appendChild(new DocumentType(rest, "", ""));
        } else {
          current.appendChild(new Comment(content));
        }
      } else if (token instanceof Tokenizer.ProcessingInstruction pi) {
        current.appendChild(new Comment("?" + pi.content()));
      } else if (token instanceof Tokenizer.StartTag start) {
        Element element = new Element(Tag.valueOf(start.name()), "");
        for (Map.Entry<String, String> attribute : start.attributes().entrySet()) {
          element.attr(attribute.getKey(), attribute.getValue() == null ? "" : attribute.getValue());
        }
        current.appendChild(element);
        if (!start.selfClosing() && !EMPTY_ELEMENTS.contains(start.name())) {
          stack.add(element);
        }
      } else if (token instanceof Tokenizer.EndTag end) {
        for (int i = stack.size() - 1; i >= 1; i--) {
          if (stack.get(i).normalName().equals(end.name())) {
            while (stack.size() > i) {
              stack.remove(stack.size() - 1);
            }
            break;
          }
        }
      }
    }

    return root;
  }

  private static boolean isRawTextElement(Element element) {
    return Tokenizer.RAW_TEXT_ELEMENTS.contains(element.normalName());
  }
}
