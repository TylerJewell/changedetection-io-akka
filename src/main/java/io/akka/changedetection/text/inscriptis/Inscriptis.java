package io.akka.changedetection.text.inscriptis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * HTML to text, laid out the way a browser would lay it out.
 *
 * <p>This is the function the whole system's answer depends on: the checksum that decides
 * "changed" is taken over its output, so a space it emits and the original does not moves every
 * verdict. It is therefore a port of the original's renderer rather than a tag-stripper --
 * block margins collapse, list bullets are counted, tables are laid out in columns, and
 * whitespace collapses per the element's own white-space mode.
 */
public final class Inscriptis {

  /** What the caller may vary: whether links and image captions are written into the text. */
  public static final class ParserConfig {
    public final Map<String, StyledElement> css;
    public final boolean displayImages;
    public final boolean deduplicateCaptions;
    public final boolean displayLinks;
    public final boolean displayAnchors;
    public final String tableCellSeparator;

    public ParserConfig() {
      this(CssProfiles.relaxed(), false, false, false, false, "  ");
    }

    public ParserConfig(
        Map<String, StyledElement> css,
        boolean displayImages,
        boolean deduplicateCaptions,
        boolean displayLinks,
        boolean displayAnchors,
        String tableCellSeparator) {
      this.css = css;
      this.displayImages = displayImages;
      this.deduplicateCaptions = deduplicateCaptions;
      this.displayLinks = displayLinks;
      this.displayAnchors = displayAnchors;
      this.tableCellSeparator = tableCellSeparator;
    }

    boolean parseAnchors() {
      return displayLinks || displayAnchors;
    }
  }

  private static final StyledElement DEFAULT_ELEMENT = new StyledElement();
  private static final String[] UL_COUNTER = {"* ", "+ ", "o ", "- "};

  private final ParserConfig config;
  private final Canvas canvas = new Canvas();
  private final List<StyledElement> tags = new ArrayList<>();
  private final List<Table> currentTable = new ArrayList<>();
  /** Each entry is either a String bullet or an Integer counter for an ordered list. */
  private final List<Object> liCounter = new ArrayList<>();

  private String lastCaption = null;
  private String linkTarget = "";

  private Inscriptis(ParserConfig config, Element root) {
    this.config = config;
    tags.add(config.css.get("body").copy().setCanvas(canvas));
    parse(root);
  }

  public static String getText(String htmlContent, ParserConfig config) {
    if (htmlContent == null) {
      return "";
    }
    String trimmed = htmlContent.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    Element root = io.akka.changedetection.text.LxmlTree.fromString(trimmed);
    if (root == null) {
      return "";
    }
    return new Inscriptis(config == null ? new ParserConfig() : config, root).text();
  }

  private String text() {
    return canvas.getText();
  }

  private StyledElement current() {
    return tags.get(tags.size() - 1);
  }

  private void parse(Element element) {
    applyStartTagLayout(element);
    startTagHandler(element);
    StyledElement cur = current();
    cur.canvas.openTag(cur);

    boolean previousWasComment = false;
    for (Node node : element.childNodes()) {
      if (node instanceof org.jsoup.nodes.DataNode dataNode) {
        // A raw-text element -- a text area, a script, a style block -- holds its content as
        // data rather than as text. The elements whose content should not appear are already
        // hidden by their own style, so what reaches here is content: a text area's default
        // value is text on the page and belongs in the comparison.
        current().write(dataNode.getWholeData());
        previousWasComment = false;
      } else if (node instanceof TextNode textNode) {
        if (previousWasComment) {
          // lxml hangs the text after a comment on the comment itself, and the renderer writes
          // such a tail straight to the canvas, without the enclosing element's affixes.
          current().canvas.write(current(), textNode.getWholeText(), null);
        } else {
          current().write(textNode.getWholeText());
        }
        previousWasComment = false;
      } else if (node instanceof Element child) {
        parse(child);
        previousWasComment = false;
      } else if (node instanceof Comment) {
        previousWasComment = true;
      }
    }

    endTagHandler(element);
    StyledElement prev = tags.remove(tags.size() - 1);
    prev.canvas.closeTag(prev);
  }

  private void applyStartTagLayout(Element element) {
    String tag = element.normalName();
    StyledElement base = config.css.get(tag);
    StyledElement next = (base == null ? DEFAULT_ELEMENT : base).copy().setTag(tag);
    applyAttributes(element, next);
    tags.add(current().refine(next));
  }

  private static void applyAttributes(Element element, StyledElement target) {
    for (Attribute attribute : element.attributes()) {
      switch (attribute.getKey()) {
        case "style" -> CssParse.applyStyleAttribute(attribute.getValue(), target);
        case "align" -> CssParse.attrHorizontalAlign(attribute.getValue(), target);
        case "valign" -> CssParse.attrVerticalAlign(attribute.getValue(), target);
        default -> {
          // No other attribute changes the layout.
        }
      }
    }
  }

  private void startTagHandler(Element element) {
    switch (element.normalName()) {
      case "table" -> {
        current().setCanvas(new Canvas());
        currentTable.add(new Table(current().canvas.leftMargin(), config.tableCellSeparator));
      }
      case "tr" -> {
        if (!currentTable.isEmpty()) {
          currentTable.get(currentTable.size() - 1).addRow();
        }
      }
      case "td", "th" -> {
        if (!currentTable.isEmpty()) {
          TableCell cell = new TableCell(current().align, current().valign);
          current().canvas = cell;
          currentTable.get(currentTable.size() - 1).addCell(cell);
        }
      }
      case "ul" -> liCounter.add(UL_COUNTER[liCounter.size() % UL_COUNTER.length]);
      case "ol" -> liCounter.add(Integer.valueOf(1));
      case "li" -> liStart(element);
      case "br" -> current().canvas.writeNewline();
      case "a" -> {
        if (config.parseAnchors()) {
          linkTarget = "";
          if (config.displayLinks) {
            linkTarget = element.hasAttr("href") ? element.attr("href") : "";
          }
          if (config.displayAnchors && linkTarget.isEmpty()) {
            linkTarget = element.hasAttr("name") ? element.attr("name") : "";
          }
          if (!linkTarget.isEmpty()) {
            current().write("[");
          }
        }
      }
      case "img" -> {
        if (config.displayImages) {
          String imageText = element.attr("alt");
          if (imageText.isEmpty()) {
            imageText = element.attr("title");
          }
          if (!imageText.isEmpty()
              && !(config.deduplicateCaptions && imageText.equals(lastCaption))) {
            current().write("[" + imageText + "]");
            lastCaption = imageText;
          }
        }
      }
      default -> {
        // Everything else is handled by its style alone.
      }
    }
  }

  private void liStart(Element element) {
    Object bullet = liCounter.isEmpty() ? "* " : liCounter.get(liCounter.size() - 1);
    String value = element.attr("value");
    if (!value.isEmpty() && isDigits(value) && bullet instanceof Integer) {
      bullet = Integer.valueOf(value);
      liCounter.set(liCounter.size() - 1, bullet);
    }
    if (bullet instanceof Integer number) {
      liCounter.set(liCounter.size() - 1, Integer.valueOf(number + 1));
      current().listBullet = number + ". ";
    } else {
      current().listBullet = (String) bullet;
    }
    current().write("");
  }

  private static boolean isDigits(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private void endTagHandler(Element element) {
    switch (element.normalName()) {
      case "table" -> tableEnd();
      case "ul", "ol" -> {
        if (!liCounter.isEmpty()) {
          liCounter.remove(liCounter.size() - 1);
        }
      }
      case "td", "th" -> tdEnd();
      case "a" -> {
        if (config.parseAnchors() && !linkTarget.isEmpty()) {
          current().write("](" + linkTarget + ")");
        }
      }
      default -> {
        // Nothing to close.
      }
    }
  }

  private void tdEnd() {
    if (!currentTable.isEmpty()) {
      current().canvas.closeTag(current());
    }
  }

  private void tableEnd() {
    if (currentTable.isEmpty()) {
      return;
    }
    tdEnd();
    Table table = currentTable.remove(currentTable.size() - 1);
    StyledElement tableTag = tags.get(tags.size() - 1);
    StyledElement parent = tags.get(tags.size() - 2);

    String outOfTableText = tableTag.canvas.getText().trim();
    if (!outOfTableText.isEmpty()) {
      parent.write(outOfTableText);
      parent.canvas.writeNewline();
    }

    parent.writeVerbatimText(table.getText());
    parent.canvas.flushInline();
  }

  /** The element map the renderer starts from, exposed so a caller can vary a single tag. */
  public static Map<String, StyledElement> copyOf(Map<String, StyledElement> css) {
    Map<String, StyledElement> out = new LinkedHashMap<>();
    for (Map.Entry<String, StyledElement> e : css.entrySet()) {
      out.put(e.getKey(), e.getValue().copy());
    }
    return out;
  }
}
