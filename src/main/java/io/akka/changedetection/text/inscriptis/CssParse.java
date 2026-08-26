package io.akka.changedetection.text.inscriptis;

import io.akka.changedetection.text.inscriptis.HtmlProperties.Display;
import io.akka.changedetection.text.inscriptis.HtmlProperties.HorizontalAlignment;
import io.akka.changedetection.text.inscriptis.HtmlProperties.VerticalAlignment;
import io.akka.changedetection.text.inscriptis.HtmlProperties.WhiteSpace;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The handful of CSS declarations the renderer reads off a style attribute.
 *
 * <p>Only these change the text: everything else on the attribute is ignored rather than
 * rejected, so a page's own stylesheet cannot make the conversion fail.
 */
public final class CssParse {

  private static final Pattern RE_UNIT = Pattern.compile("(-?[0-9.]+)(\\w+)");

  private CssParse() {}

  public static void applyStyleAttribute(String styleAttribute, StyledElement element) {
    for (String directive : styleAttribute.toLowerCase(Locale.ROOT).split(";")) {
      int colon = directive.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = directive.substring(0, colon).trim();
      String value = directive.substring(colon + 1).trim();
      key = key.replace("-webkit-", "").replace("-", "_");
      switch (key) {
        case "display" -> attrDisplay(value, element);
        case "white_space" -> attrWhiteSpace(value, element);
        case "margin_top", "margin_before" -> {
          Integer em = getEm(value);
          if (em != null) {
            element.marginBefore = em;
          }
        }
        case "margin_bottom", "margin_after" -> {
          Integer em = getEm(value);
          if (em != null) {
            element.marginAfter = em;
          }
        }
        case "padding_left", "padding_start" -> {
          Integer em = getEm(value);
          if (em != null) {
            element.paddingInline = em;
          }
        }
        case "horizontal_align" -> attrHorizontalAlign(value, element);
        case "vertical_align" -> attrVerticalAlign(value, element);
        default -> {
          // Any other declaration has no effect on the text.
        }
      }
    }
  }

  private static Integer getEm(String length) {
    Matcher m = RE_UNIT.matcher(length);
    if (!m.find()) {
      return null;
    }
    double value;
    try {
      value = Double.parseDouble(m.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
    String unit = m.group(2);
    if (!unit.equals("em") && !unit.equals("qem") && !unit.equals("rem")) {
      return pyRound(value / 8);
    }
    return pyRound(value);
  }

  /** Python rounds a half to the even neighbour, which Math.round does not. */
  private static int pyRound(double v) {
    return (int) Math.rint(v);
  }

  public static void attrDisplay(String value, StyledElement element) {
    if (element.display == Display.NONE) {
      return;
    }
    if (value.equals("block")) {
      element.display = Display.BLOCK;
    } else if (value.equals("none")) {
      element.display = Display.NONE;
    } else {
      element.display = Display.INLINE;
    }
  }

  public static void attrWhiteSpace(String value, StyledElement element) {
    if (value.equals("normal") || value.equals("nowrap")) {
      element.whitespace = WhiteSpace.NORMAL;
    } else if (value.equals("pre") || value.equals("pre-line") || value.equals("pre-wrap")) {
      element.whitespace = WhiteSpace.PRE;
    }
  }

  public static void attrHorizontalAlign(String value, StyledElement element) {
    switch (value) {
      case "left" -> element.align = HorizontalAlignment.LEFT;
      case "right" -> element.align = HorizontalAlignment.RIGHT;
      case "center" -> element.align = HorizontalAlignment.CENTER;
      default -> {
        // An alignment the renderer does not know leaves the inherited one in place.
      }
    }
  }

  public static void attrVerticalAlign(String value, StyledElement element) {
    switch (value) {
      case "top" -> element.valign = VerticalAlignment.TOP;
      case "middle" -> element.valign = VerticalAlignment.MIDDLE;
      case "bottom" -> element.valign = VerticalAlignment.BOTTOM;
      default -> {
        // Same as above.
      }
    }
  }
}
