package io.akka.changedetection.text;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * The parsed tree as a document an XPath engine can walk.
 *
 * <p>No namespace is declared on anything, because the original's HTML tree has none: an
 * unprefixed step like {@code //p} matches there, and would match nothing at all against a tree
 * whose elements had been placed in the XHTML namespace.
 */
public final class DomBuilder {

  private DomBuilder() {}

  public static org.w3c.dom.Document toDom(Element root) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      org.w3c.dom.Document dom = factory.newDocumentBuilder().newDocument();
      org.w3c.dom.Element element = dom.createElement(safeName(root.normalName()));
      copyAttributes(root, element, dom);
      dom.appendChild(element);
      appendChildren(root, element, dom);
      return dom;
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void appendChildren(
      Element source, org.w3c.dom.Element target, org.w3c.dom.Document dom) {
    for (Node child : source.childNodes()) {
      if (child instanceof TextNode textNode) {
        target.appendChild(dom.createTextNode(textNode.getWholeText()));
      } else if (child instanceof DataNode dataNode) {
        target.appendChild(dom.createTextNode(dataNode.getWholeData()));
      } else if (child instanceof Comment comment) {
        target.appendChild(dom.createComment(comment.getData()));
      } else if (child instanceof Element element) {
        org.w3c.dom.Element created = dom.createElement(safeName(element.normalName()));
        copyAttributes(element, created, dom);
        target.appendChild(created);
        appendChildren(element, created, dom);
      }
    }
  }

  private static void copyAttributes(
      Element source, org.w3c.dom.Element target, org.w3c.dom.Document dom) {
    for (org.jsoup.nodes.Attribute attribute : source.attributes()) {
      String name = attribute.getKey();
      if (!isValidName(name)) {
        continue;
      }
      target.setAttribute(name, attribute.getValue());
    }
  }

  private static String safeName(String name) {
    return isValidName(name) ? name : "unknown-element";
  }

  private static boolean isValidName(String name) {
    if (name.isEmpty()) {
      return false;
    }
    char first = name.charAt(0);
    if (!Character.isLetter(first) && first != '_' && first != ':') {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.' && c != ':') {
        return false;
      }
    }
    return true;
  }
}
