package io.akka.changedetection.processors;

import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.text.HtmlTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * A feed laid out as ordinary markup, one article per entry.
 *
 * <p>Without this a feed is compared as one long run of text and a change anywhere in it moves
 * the whole thing. Laid out this way, each entry is its own block with its fields labelled, so
 * an added entry is an added block and a filter can be written against it as a selector.
 */
public final class RssTools {

  /** How one entry is written out. Kept as the original's template, verbatim. */
  private static final String ENTRY_TEMPLATE =
      "<article class=\"rss-item\" id=\"{{ entry.id|replace('\"', '')|replace(' ', '-') }}\">"
          + "{%- if entry.title -%}Title: {{ entry.title }}<br>{%- endif -%}\n"
          + "{%- if entry.link -%}<strong>Link:</strong> <a href=\"{{ entry.link }}\">"
          + "{{ entry.link }}</a><br>\n{%- endif -%}\n"
          + "{%- if entry.id -%}\n<strong>Guid:</strong> {{ entry.id }}<br>\n{%- endif -%}\n"
          + "{%- if entry.published -%}\n<strong>PubDate:</strong> {{ entry.published }}<br>\n"
          + "{%- endif -%}\n"
          + "{%- if entry.updated and entry.updated != entry.published -%}\n"
          + "<strong>Updated:</strong> {{ entry.updated }}<br>\n{%- endif -%}\n"
          + "{%- if entry.author -%}\n<strong>Author:</strong> {{ entry.author }}<br>\n"
          + "{%- endif -%}\n"
          + "{%- if entry.tags -%}\n<strong>Tags:</strong> {% for tag in entry.tags -%}\n"
          + "{{ tag }}\n{%- if not loop.last %}, {% endif -%}\n{%- endfor %}<br>\n{%- endif -%}\n"
          + "{%- if entry.category -%}\n<strong>Category:</strong> {{ entry.category }}<br>\n"
          + "{%- endif -%}\n"
          + "{%- if entry.comments -%}\n<strong>Comments:</strong> "
          + "<a href=\"{{ entry.comments }}\">{{ entry.comments }}</a><br>\n{%- endif -%}\n"
          + "{%- if entry.content -%}\n<strong>Content:</strong> {{ entry.content | safe }}\n"
          + "{%- elif entry.summary -%}\n<strong>Summary:</strong> {{ entry.summary | safe }}\n"
          + "{%- endif -%}</article>";

  private RssTools() {}

  /** One entry of a feed, with the fields the layout reads. */
  public record Entry(Map<String, Object> values) {}

  public static String formatItems(String rssContent) {
    try {
      List<Entry> entries = parse(rssContent);
      Environment environment = new Environment();
      List<String> rendered = new ArrayList<>();
      for (Entry entry : entries) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("entry", entry.values());
        rendered.add(environment.renderString(ENTRY_TEMPLATE, context).strip());
      }
      List<String> itemsHtml = new ArrayList<>();
      for (int index = 0; index < rendered.size(); index++) {
        List<String> classes = new ArrayList<>();
        classes.add("rss-item");
        if (index == 0) {
          classes.add("first");
        }
        if (index == rendered.size() - 1) {
          classes.add("last");
        }
        classes.add("item-" + (index + 1));
        itemsHtml.add(
            "<div class=\"" + String.join(" ", classes) + "\">" + rendered.get(index) + "</div>");
      }
      return "<html><body>\n" + String.join("\n<br>", itemsHtml) + "\n</body></html>";
    } catch (RuntimeException e) {
      // A feed that cannot be read is compared as it came rather than failing the check,
      // which keeps a malformed feed visible to the operator instead of hiding it.
      return rssContent;
    }
  }

  /** The entries of a feed, in either of the two families of feed format. */
  public static List<Entry> parse(String rssContent) {
    Document document =
        org.jsoup.Jsoup.parse(rssContent, "", Parser.xmlParser());
    List<Entry> entries = new ArrayList<>();
    for (Element element : document.select("item, entry")) {
      Map<String, Object> values = new LinkedHashMap<>();
      put(values, "title", text(element, "title"));
      put(values, "link", link(element));
      put(values, "id", firstNonEmpty(text(element, "guid"), text(element, "id")));
      put(values, "published",
          firstNonEmpty(text(element, "pubDate"), text(element, "published"),
              text(element, "dc|date")));
      put(values, "updated", text(element, "updated"));
      put(values, "author",
          firstNonEmpty(text(element, "author"), text(element, "dc|creator"),
              text(element, "author > name")));
      put(values, "category", text(element, "category"));
      put(values, "comments", text(element, "comments"));
      put(values, "summary",
          firstNonEmpty(text(element, "description"), text(element, "summary")));
      put(values, "content",
          firstNonEmpty(text(element, "content|encoded"), text(element, "content")));
      List<String> tags = new ArrayList<>();
      for (Element tag : element.select("category")) {
        String term = tag.hasAttr("term") ? tag.attr("term") : tag.text();
        if (!term.isBlank()) {
          tags.add(term.strip());
        }
      }
      if (!tags.isEmpty()) {
        values.put("tags", tags);
      }
      entries.add(new Entry(values));
    }
    return entries;
  }

  private static void put(Map<String, Object> values, String key, String value) {
    if (value != null && !value.isEmpty()) {
      values.put(key, value);
    }
  }

  private static String text(Element element, String selector) {
    Element found = element.selectFirst(selector);
    if (found == null) {
      return null;
    }
    String value = found.wholeText();
    if (value == null || value.isBlank()) {
      value = found.text();
    }
    return value == null ? null : value.strip();
  }

  /** A feed's entry address, which one family writes as text and the other as an attribute. */
  private static String link(Element element) {
    Element found = element.selectFirst("link");
    if (found == null) {
      return null;
    }
    if (found.hasAttr("href")) {
      return found.attr("href").strip();
    }
    String value = found.text();
    return value == null ? null : value.strip();
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /** The kind of feed, which decides nothing here but is part of what the original reads. */
  public static boolean isAtom(String content) {
    return content != null && content.toLowerCase(Locale.ROOT).contains("<feed");
  }

  /** Embedded markup unwrapped, for a feed compared without the layout above. */
  public static String cdataToText(String content) {
    return HtmlTools.cdataInDocumentToText(content);
  }
}
