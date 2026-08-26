package io.akka.changedetection.text;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * The two XPath dialects the original offers, kept apart because they answer differently.
 *
 * <p>{@code xpath:} is the later dialect and registers a document's default namespace under the
 * empty prefix, so an unprefixed step matches inside an RSS feed. {@code xpath1:} is the
 * earlier one and does not, which is why the original documents {@code local-name()} for that
 * case. Collapsing the two would silently change the answer for every feed.
 *
 * <p>The tree also differs between the two things XPath is used for. A filter runs against the
 * fragment-aware tree, which wraps a fragment in an element an expression can then match; a
 * subtractive selector runs against the whole-document tree, which does not.
 */
public final class XPathFilter {

  private static final Processor PROCESSOR = new Processor(false);

  private XPathFilter() {}

  /** The later dialect: expressions run against a namespace-aware tree. */
  public static String xpathFilter(
      String xpathFilter, String htmlContent, boolean appendPrettyLineFormatting, boolean isXml) {
    try {
      org.w3c.dom.Document dom = filterDom(htmlContent, isXml);
      XdmNode root = PROCESSOR.newDocumentBuilder().wrap(dom);
      XPathCompiler compiler = PROCESSOR.newXPathCompiler();
      compiler.declareNamespace("re", "http://exslt.org/regular-expressions");
      String defaultNs = defaultNamespaceOf(dom);
      if (defaultNs != null && !defaultNs.isEmpty()) {
        compiler.declareNamespace("", defaultNs);
      }
      XPathSelector selector = compiler.compile(xpathFilter.strip()).load();
      selector.setContextItem(root);
      XdmValue value = selector.evaluate();

      StringBuilder htmlBlock = new StringBuilder();
      for (XdmItem item : value) {
        String tagName = null;
        if (item instanceof XdmNode node && node.getNodeName() != null) {
          tagName = node.getNodeName().getLocalName().toLowerCase(Locale.ROOT);
        }
        if (appendPrettyLineFormatting
            && htmlBlock.length() > 0
            && (tagName == null || !isLineProducing(tagName))) {
          htmlBlock.append(HtmlTools.TEXT_FILTER_LIST_LINE_SUFFIX);
        }
        htmlBlock.append(serialise(item, isXml));
      }
      return htmlBlock.toString();
    } catch (XPathFilterException e) {
      throw e;
    } catch (Exception e) {
      throw new XPathFilterException(String.valueOf(e.getMessage()), e);
    }
  }

  /** The earlier dialect, which has no way to spell a default namespace. */
  public static String xpath1Filter(
      String xpathFilter, String htmlContent, boolean appendPrettyLineFormatting, boolean isXml) {
    try {
      org.w3c.dom.Document dom = filterDom(htmlContent, isXml);
      XPathExpression expression =
          XPathFactory.newInstance().newXPath().compile(xpathFilter.strip());
      Object evaluated = expression.evaluate(dom, XPathConstants.NODESET);
      NodeList nodes = (NodeList) evaluated;
      StringBuilder htmlBlock = new StringBuilder();
      for (int i = 0; i < nodes.getLength(); i++) {
        Node node = nodes.item(i);
        String tagName =
            node.getNodeType() == Node.ELEMENT_NODE
                ? node.getNodeName().toLowerCase(Locale.ROOT)
                : null;
        if (appendPrettyLineFormatting
            && htmlBlock.length() > 0
            && (tagName == null || !isLineProducing(tagName))) {
          htmlBlock.append(HtmlTools.TEXT_FILTER_LIST_LINE_SUFFIX);
        }
        htmlBlock.append(serialiseDomNode(node, isXml));
      }
      return htmlBlock.toString();
    } catch (XPathFilterException e) {
      throw e;
    } catch (Exception e) {
      throw new XPathFilterException(String.valueOf(e.getMessage()), e);
    }
  }

  /** Elements matched by any of the selectors removed from the whole-document tree. */
  public static String removeByXPath(List<String> selectors, String htmlContent) {
    if (htmlContent == null || htmlContent.strip().isEmpty()) {
      // The original's tree-level parser returns nothing for an empty document and the caller
      // then fails on it, so an empty document is an error rather than an empty result.
      throw new XPathFilterException("no document to apply a subtractive selector to", null);
    }
    org.w3c.dom.Document dom = DomBuilder.toDom(LxmlTree.document(htmlContent));
    List<Node> toRemove = new ArrayList<>();
    try {
      javax.xml.xpath.XPath xpath = XPathFactory.newInstance().newXPath();
      for (String selector : selectors) {
        NodeList nodes = (NodeList) xpath.evaluate(selector, dom, XPathConstants.NODESET);
        for (int i = 0; i < nodes.getLength(); i++) {
          toRemove.add(nodes.item(i));
        }
      }
    } catch (Exception e) {
      return htmlContent;
    }
    if (toRemove.isEmpty()) {
      // The original returns the document it was given rather than a re-serialised copy when
      // nothing matched -- and the two are not the same string, because the copy has the
      // document shell around it and the fragment it was handed did not.
      return htmlContent;
    }
    for (Node node : toRemove) {
      if (node.getParentNode() != null) {
        node.getParentNode().removeChild(node);
      }
    }
    return LxmlSerializer.tostring(dom.getDocumentElement());
  }

  private static boolean isLineProducing(String name) {
    return name.equals("br") || name.equals("hr") || name.equals("div") || name.equals("p");
  }

  private static org.w3c.dom.Document filterDom(String content, boolean isXml) throws Exception {
    if (isXml) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setExpandEntityReferences(false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(new InputSource(new StringReader(content)));
    }
    org.jsoup.nodes.Element tree = LxmlTree.fromString(content);
    if (tree == null) {
      throw new XPathFilterException("Document is empty", null);
    }
    return DomBuilder.toDom(tree);
  }

  private static String defaultNamespaceOf(org.w3c.dom.Document dom) {
    Node root = dom.getDocumentElement();
    if (root == null) {
      return null;
    }
    NamedNodeMap attributes = root.getAttributes();
    if (attributes == null) {
      return null;
    }
    Node xmlns = attributes.getNamedItem("xmlns");
    return xmlns == null ? null : xmlns.getNodeValue();
  }

  private static String serialise(XdmItem item, boolean isXml) {
    if (!(item instanceof XdmNode node)) {
      return item.getStringValue();
    }
    switch (node.getNodeKind()) {
      case TEXT:
      case ATTRIBUTE:
      case COMMENT:
        return node.getStringValue();
      default:
        Object external = node.getExternalNode();
        return external instanceof Node domNode
            ? serialiseDomNode(domNode, isXml)
            : node.toString();
    }
  }

  private static String serialiseDomNode(Node node, boolean isXml) {
    if (node == null) {
      return "";
    }
    if (node.getNodeType() == Node.TEXT_NODE
        || node.getNodeType() == Node.ATTRIBUTE_NODE
        || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      return node.getNodeValue() == null ? "" : node.getNodeValue();
    }
    return LxmlSerializer.tostringPretty(node);
  }

  /** Raised where the original lets an XPath error reach the worker as a check failure. */
  public static class XPathFilterException extends RuntimeException {
    public XPathFilterException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
