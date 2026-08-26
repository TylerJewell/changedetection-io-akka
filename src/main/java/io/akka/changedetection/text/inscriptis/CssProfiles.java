package io.akka.changedetection.text.inscriptis;

import io.akka.changedetection.text.inscriptis.HtmlProperties.Display;
import io.akka.changedetection.text.inscriptis.HtmlProperties.WhiteSpace;
import java.util.LinkedHashMap;
import java.util.Map;

/** The default styles a browser applies, in the two profiles the renderer ships. */
public final class CssProfiles {

  private CssProfiles() {}

  private static StyledElement block() {
    return new StyledElement("default", "", "", Display.BLOCK, 0, 0, 0, "", null, false);
  }

  private static StyledElement blockWithMargin() {
    return new StyledElement("default", "", "", Display.BLOCK, 1, 1, 0, "", null, false);
  }

  public static Map<String, StyledElement> strict() {
    Map<String, StyledElement> css = new LinkedHashMap<>();
    css.put(
        "body",
        new StyledElement(
            "body", "", "", Display.INLINE, 0, 0, 0, "", WhiteSpace.NORMAL, false));
    css.put("head", new StyledElement("head", "", "", Display.NONE, 0, 0, 0, "", null, false));
    css.put("link", new StyledElement("link", "", "", Display.NONE, 0, 0, 0, "", null, false));
    css.put("meta", new StyledElement("meta", "", "", Display.NONE, 0, 0, 0, "", null, false));
    css.put("script", new StyledElement("script", "", "", Display.NONE, 0, 0, 0, "", null, false));
    css.put("title", new StyledElement("title", "", "", Display.NONE, 0, 0, 0, "", null, false));
    css.put("style", new StyledElement("style", "", "", Display.NONE, 0, 0, 0, "", null, false));

    for (String tag : new String[] {"p", "figure", "h1", "h2", "h3", "h4", "h5", "h6"}) {
      css.put(tag, blockWithMargin());
    }
    css.put(
        "ul", new StyledElement("ul", "", "", Display.BLOCK, 0, 0, 4, "", null, false));
    css.put(
        "ol", new StyledElement("ol", "", "", Display.BLOCK, 0, 0, 4, "", null, false));
    for (String tag :
        new String[] {
          "li",
          "address",
          "article",
          "aside",
          "div",
          "footer",
          "header",
          "hgroup",
          "layer",
          "main",
          "nav",
          "figcaption",
          "blockquote"
        }) {
      css.put(tag, block());
    }
    css.put(
        "q",
        new StyledElement("q", "\"", "\"", Display.INLINE, 0, 0, 0, "", null, false));
    for (String tag : new String[] {"pre", "xmp", "listing", "plaintext"}) {
      css.put(
          tag,
          new StyledElement(tag, "", "", Display.BLOCK, 0, 0, 0, "", WhiteSpace.PRE, false));
    }
    return css;
  }

  /** The profile the renderer uses by default, and the one the original never overrides. */
  public static Map<String, StyledElement> relaxed() {
    Map<String, StyledElement> css = strict();
    css.put("div", new StyledElement("div", "", "", Display.BLOCK, 0, 0, 2, "", null, false));
    css.put(
        "span",
        new StyledElement("span", " ", " ", Display.INLINE, 0, 0, 0, "", null, true));
    return css;
  }
}
