package io.akka.changedetection.text;

import io.akka.changedetection.text.inscriptis.CssProfiles;
import io.akka.changedetection.text.inscriptis.Inscriptis;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/** Turning a fetched document into the text the rules are applied to. */
public final class HtmlTools {

  /**
   * Appended between two matches of the same filter so each match reliably starts a new line in
   * the rendered text. Divs, paragraphs and rules already produce one, so those are skipped.
   */
  public static final String TEXT_FILTER_LIST_LINE_SUFFIX = "<br>";

  public static final List<String> LD_JSON_PRODUCT_OFFER_SELECTORS =
      List.of("json:$..offers", "json:$..Offers");

  private static final Pattern TITLE_RE =
      Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern META_CS =
      Pattern.compile(
          "<meta[^>]+charset=[\"']?\\s*([a-z0-9_\\-:+.]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern CDATA =
      Pattern.compile("<!\\[CDATA\\[(\\s*(?:.(?<!\\]\\]>)\\s*)*)\\]\\]>", Pattern.DOTALL);
  private static final Pattern EMPTY_COMMENT = Pattern.compile("<!--\\s+-->");
  private static final Pattern HIDING_STYLE =
      Pattern.compile(
          "\\b(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern LD_JSON_MARKER =
      Pattern.compile("application/ld\\+json", Pattern.CASE_INSENSITIVE);
  private static final Pattern PRICE_MARKER = Pattern.compile("\"price\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern PRICE_CURRENCY_MARKER =
      Pattern.compile("\"pricecurrency\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern RSS_TITLE_OPEN = Pattern.compile("<title([\\s>])");
  private static final Pattern RSS_TITLE_CLOSE = Pattern.compile("</title>");

  /** Thrown where the original raises JSONNotFound: a document with no parsable JSON in it. */
  public static class JsonNotFound extends RuntimeException {
    public JsonNotFound(String message) {
      super(message);
    }
  }

  private HtmlTools() {}

  // ---------------------------------------------------------------- filters

  /**
   * A document as something to run selectors against.
   *
   * <p>The original selects with a parser that adds nothing: a fragment of two paragraphs has
   * no {@code body} element in it, so the selector {@code body} matches nothing and the filter
   * is reported as not found -- which is a visible outcome, not a detail, because a filter that
   * matches nothing raises and the watch records "no filters were found". A parser that
   * supplies the missing shell answers that selector instead, and the watch silently keeps
   * checking against the whole page.
   */
  public static final class Soup {
    final Element root;

    Soup(Element root) {
      this.root = root;
    }

    public Elements select(String query) {
      return root.select(query);
    }

    /** The markup back out, in the same shape it went in. */
    public String serialise() {
      return SoupSerializer.innerHtml(root);
    }
  }

  public static Soup soup(String htmlContent) {
    return new Soup(SoupParser.parse(htmlContent == null ? "" : htmlContent));
  }

  /** A CSS selector applied to a document, returning the matched markup rather than text. */
  public static String includeFilters(
      String includeFilters, String htmlContent, boolean appendPrettyLineFormatting) {
    Soup soup = soup(htmlContent);
    StringBuilder htmlBlock = new StringBuilder();
    Elements matched;
    try {
      matched = soup.select(includeFilters);
    } catch (Exception e) {
      return "";
    }
    for (Element element : matched) {
      if (appendPrettyLineFormatting
          && htmlBlock.length() > 0
          && !isLineProducingTag(element.normalName())) {
        htmlBlock.append(TEXT_FILTER_LIST_LINE_SUFFIX);
      }
      htmlBlock.append(SoupSerializer.outerHtml(element));
    }
    return htmlBlock.toString();
  }

  private static boolean isLineProducingTag(String name) {
    return name.equals("br") || name.equals("hr") || name.equals("div") || name.equals("p");
  }

  /**
   * Elements matching the given selectors removed. CSS and XPath selectors are separated first
   * and each family applied in one pass, so removing one element cannot shift the index of
   * another before it is found.
   */
  public static String elementRemoval(List<String> selectors, String htmlContent) {
    String modified = htmlContent;
    List<String> cssSelectors = new ArrayList<>();
    List<String> xpathSelectors = new ArrayList<>();

    for (String selector : selectors) {
      String trimmed = selector.strip();
      if (trimmed.startsWith("xpath:") || trimmed.startsWith("xpath1:") || trimmed.startsWith("//")) {
        String xpath = trimmed;
        if (xpath.startsWith("xpath:")) {
          xpath = xpath.substring("xpath:".length());
        } else if (xpath.startsWith("xpath1:")) {
          xpath = xpath.substring("xpath1:".length());
        }
        xpathSelectors.add(xpath);
      } else {
        cssSelectors.add(stripCommas(trimmed));
      }
    }

    if (!xpathSelectors.isEmpty()) {
      modified = XPathFilter.removeByXPath(xpathSelectors, modified);
    }
    if (!cssSelectors.isEmpty()) {
      Set<String> unique = new LinkedHashSet<>(cssSelectors);
      String combined = String.join(" , ", unique);
      modified = subtractiveCssSelector(combined, modified);
    }
    return modified;
  }

  private static String stripCommas(String s) {
    int a = 0;
    int b = s.length();
    while (a < b && s.charAt(a) == ',') {
      a++;
    }
    while (b > a && s.charAt(b - 1) == ',') {
      b--;
    }
    return s.substring(a, b);
  }

  public static String subtractiveCssSelector(String cssSelector, String content) {
    Soup soup = soup(content);
    Elements toRemove;
    try {
      toRemove = soup.select(cssSelector);
    } catch (Exception e) {
      return content;
    }
    if (toRemove.isEmpty()) {
      // Returning the original rather than a re-serialised copy is what the original does, and
      // it matters: re-serialising normalises attribute quoting and void tags even when nothing
      // was removed, which moves the checksum on a page no selector touched.
      return content;
    }
    for (Element element : toRemove) {
      element.remove();
    }
    return soup.serialise();
  }

  // ------------------------------------------------------- ignore / trigger

  /** What the mode argument of the ignore scan asks for. */
  public enum StripMode {
    CONTENT,
    LINE_NUMBERS
  }

  /**
   * The lines matched by a word list, either removed or reported.
   *
   * <p>An entry is a regular expression when it is slash-enclosed and a case-insensitive
   * substring otherwise. An expression asking for dot-matches-all or multi-line is run against
   * the whole document and every line it spans is marked, which is the only way a rule can
   * match across a line break.
   */
  public static Object stripIgnoreText(String content, List<String> wordlist, StripMode mode) {
    List<String> ignoreText = new ArrayList<>();
    List<Pattern> ignoreRegex = new ArrayList<>();
    List<Pattern> ignoreRegexMultiline = new ArrayList<>();
    Set<Integer> ignoredLines = new TreeSet<>();

    if (content == null || content.isEmpty()) {
      return mode == StripMode.LINE_NUMBERS ? new ArrayList<Integer>() : "";
    }

    for (String k : wordlist) {
      if (k == null || k.strip().isEmpty()) {
        continue;
      }
      if (PyRegex.isPerlStyle(k)) {
        Pattern compiled = PyRegex.compile(PyRegex.perlStyleToOptions(k));
        if (PyRegex.isMultilineFlavour(k)) {
          ignoreRegexMultiline.add(compiled);
        } else {
          ignoreRegex.add(compiled);
        }
      } else {
        ignoreText.add(k.strip());
      }
    }

    List<String> lines = PythonText.splitLinesKeepEnds(content);

    for (Pattern r : ignoreRegexMultiline) {
      Matcher m = r.matcher(content);
      while (m.find()) {
        int endLine = PythonText.splitLinesKeepEnds(content.substring(0, m.end())).size();
        int matchLines = PythonText.splitLinesKeepEnds(content.substring(m.start(), m.end())).size();
        int startLine = endLine - matchLines;
        if (endLine - startLine <= 1) {
          ignoredLines.add(startLine);
        } else {
          for (int i = startLine; i < endLine; i++) {
            ignoredLines.add(i);
          }
        }
      }
    }

    for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
      String line = lines.get(lineIndex);
      boolean gotMatch = false;
      String lowered = line.toLowerCase(Locale.ROOT);
      for (String l : ignoreText) {
        if (lowered.contains(l.toLowerCase(Locale.ROOT))) {
          gotMatch = true;
          break;
        }
      }
      if (!gotMatch) {
        for (Pattern r : ignoreRegex) {
          if (r.matcher(line).find()) {
            gotMatch = true;
            break;
          }
        }
      }
      if (gotMatch) {
        ignoredLines.add(lineIndex);
      }
    }

    Set<Integer> valid = new TreeSet<>();
    for (int i : ignoredLines) {
      if (i >= 0 && i < lines.size()) {
        valid.add(i);
      }
    }

    if (mode == StripMode.LINE_NUMBERS) {
      List<Integer> out = new ArrayList<>();
      for (int i : valid) {
        out.add(i + 1);
      }
      return out;
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      if (!valid.contains(i)) {
        sb.append(lines.get(i));
      }
    }
    return sb.toString();
  }

  public static String stripIgnoreText(String content, List<String> wordlist) {
    return (String) stripIgnoreText(content, wordlist, StripMode.CONTENT);
  }

  @SuppressWarnings("unchecked")
  public static List<Integer> ignoredLineNumbers(String content, List<String> wordlist) {
    Object result = stripIgnoreText(content, wordlist, StripMode.LINE_NUMBERS);
    return result instanceof List ? (List<Integer>) result : new ArrayList<>();
  }

  /** The lines a trigger matched, in document order, for the notification token. */
  public static List<String> getTriggeredText(String content, List<String> triggerText) {
    List<Integer> result = ignoredLineNumbers(content, triggerText);
    List<String> triggered = new ArrayList<>();
    int i = 1;
    for (String p : PythonText.splitLines(content)) {
      if (result.contains(i)) {
        triggered.add(p);
      }
      i++;
    }
    return triggered;
  }

  // ------------------------------------------------------------ conversions

  /** CDATA sections replaced by the text of the markup inside them, XML-escaped. */
  public static String cdataInDocumentToText(String htmlContent) {
    Matcher m = CDATA.matcher(htmlContent);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String text = m.group(1);
      String replaced = xmlEscape(htmlToText(text, false, false)).strip();
      m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String xmlEscape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /**
   * HTML to plain text.
   *
   * <p>The head and the tags a text renderer cannot make anything of are removed before the
   * renderer sees them, because a single-page application routinely puts megabytes of style and
   * script in the head and the renderer gives up on it. A body hidden with a display rule has
   * that rule removed, because the renderer honours it and the page would come back empty.
   */
  public static String htmlToText(String htmlContent, boolean renderAnchorTagContent, boolean isRss) {
    if (htmlContent == null) {
      return "";
    }
    String working = htmlContent;
    if (isRss) {
      working = RSS_TITLE_OPEN.matcher(working).replaceAll("<h1$1");
      working = RSS_TITLE_CLOSE.matcher(working).replaceAll("</h1>");
    } else {
      Element soup = SoupParser.parse(working);
      for (Element tag :
          soup.select("head, script, style, noscript, svg, math, canvas, iframe, template")) {
        tag.remove();
      }
      for (Element body : soup.select("body")) {
        if (body.hasAttr("style") && HIDING_STYLE.matcher(body.attr("style")).find()) {
          body.removeAttr("style");
        }
      }
      working = SoupSerializer.innerHtml(soup);
    }

    Inscriptis.ParserConfig config;
    if (renderAnchorTagContent) {
      config = new Inscriptis.ParserConfig(CssProfiles.relaxed(), false, false, true, false, "  ");
    } else {
      config = new Inscriptis.ParserConfig();
    }
    return Inscriptis.getText(working, config);
  }

  /** Sites that break a price into pieces with empty comments so a renderer cannot read it. */
  public static String workaroundsForObfuscations(String content) {
    if (content == null || content.isEmpty()) {
      return content;
    }
    return EMPTY_COMMENT.matcher(content).replaceAll("");
  }

  /** Whether the page carries linked-data product pricing worth offering to track. */
  public static boolean hasLdJsonProductInfo(String content) {
    try {
      return LD_JSON_MARKER.matcher(content).find()
          && PRICE_MARKER.matcher(content).find()
          && PRICE_CURRENCY_MARKER.matcher(content).find();
    } catch (Exception e) {
      return false;
    }
  }

  /** The first element of the given name, as text. */
  public static String extractElement(String find, String htmlContent) {
    Document soup = parseFragmentPreserving(htmlContent);
    Element result = soup.selectFirst(find);
    if (result == null) {
      return null;
    }
    String text = result.ownText();
    return text.isEmpty() ? null : text.strip();
  }

  /**
   * The document title, found by locating the tag and decoding a window around it rather than
   * decoding the whole document -- some pages carry the title far past any fixed scan limit.
   */
  public static String extractTitle(String data) {
    if (data == null) {
      return null;
    }
    int tagPos = data.toLowerCase(Locale.ROOT).indexOf("<title");
    if (tagPos < 0) {
      return null;
    }
    int window = 131072;
    String prefix = data.substring(tagPos, Math.min(data.length(), tagPos + window));
    Matcher m = TITLE_RE.matcher(prefix);
    if (!m.find()) {
      return null;
    }
    String title =
        Parser.unescapeEntities(String.join(" ", PythonText.splitOnWhitespace(m.group(1))), false)
            .strip();
    return title.length() > 2000 ? title.substring(0, 2000) : title;
  }

  public static String charsetFromMeta(String head) {
    Matcher m = META_CS.matcher(head);
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
  }

  private static final Pattern WHOLE_DOCUMENT =
      Pattern.compile("^\\s*<(?:html|!doctype)", Pattern.CASE_INSENSITIVE);

  public static boolean looksLikeWholeDocument(String html) {
    return html != null && WHOLE_DOCUMENT.matcher(html).find();
  }

  private static String fragmentHtml(Document soup) {
    StringBuilder sb = new StringBuilder();
    Element head = soup.head();
    Element body = soup.body();
    if (head != null) {
      sb.append(head.html());
    }
    if (body != null) {
      sb.append(body.html());
    }
    return sb.toString();
  }

  private static final Pattern PRE_START_THEN_NEWLINE =
      Pattern.compile("(<(?:pre|textarea|listing|xmp)\\b[^>]*>)(\\r\\n|\\n|\\r)",
          Pattern.CASE_INSENSITIVE);

  /**
   * A newline written immediately after a preformatted start tag, kept.
   *
   * <p>The later HTML parsers drop exactly one such newline, on the grounds that it is markup
   * layout rather than content. The original's parser keeps it, and inside a preformatted block
   * that newline is a line of the extracted text -- so a page whose preformatted block opens on
   * its own line comes out one line shorter here than there. Doubling it before parsing lets
   * the parser drop one and leaves the one the original would have kept.
   */
  public static String keepLeadingNewlineInPreformatted(String html) {
    if (html == null || html.isEmpty()) {
      return html;
    }
    Matcher m = PRE_START_THEN_NEWLINE.matcher(html);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + m.group(2)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** A parse that keeps the document shape jsoup would otherwise normalise away. */
  public static Document parseFragmentPreserving(String html) {
    Document doc =
        Jsoup.parse(
            html == null ? "" : keepLeadingNewlineInPreformatted(html), "", Parser.htmlParser());
    doc.outputSettings().prettyPrint(false);
    return doc;
  }
}
