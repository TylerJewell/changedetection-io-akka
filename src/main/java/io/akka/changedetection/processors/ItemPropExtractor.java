package io.akka.changedetection.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.changedetection.text.HtmlTools;
import io.akka.changedetection.text.JsonFilter;
import io.akka.changedetection.text.PythonJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The price and availability a product page states about itself in machine-readable form.
 *
 * <p>Pages state it in several vocabularies at once and rarely agree with themselves, so all of
 * them are gathered and then searched, rather than one being preferred. Where they disagree
 * about the price, the page is refused: a product page carrying two different prices is a
 * category page or a page with an offer, and picking either would be a guess the operator
 * cannot see.
 */
public final class ItemPropExtractor {

  private static final Pattern SCHEMA_PREFIX =
      Pattern.compile("^(https|http)://schema\\.org/", Pattern.CASE_INSENSITIVE);

  /** Raised where the page states more than one price. */
  public static class MoreThanOnePriceFound extends RuntimeException {
    public MoreThanOnePriceFound() {
      super("More than one price found");
    }
  }

  private ItemPropExtractor() {}

  public static Restock extract(String htmlContent) {
    Restock value = new Restock();
    JsonNode gathered = gather(htmlContent);
    if (gathered.isEmpty()) {
      return value;
    }

    List<Double> prices = deduplicatePrices(findAny(gathered, List.of("price", "Price")));
    if (!prices.isEmpty()) {
      if (prices.size() > 1) {
        throw new MoreThanOnePriceFound();
      }
      value.price = prices.get(0);
    }

    List<JsonNode> currencies =
        findAny(gathered, List.of("pricecurrency", "currency", "priceCurrency"));
    if (!currencies.isEmpty()) {
      value.currency = currencies.get(0).asText();
    }

    List<JsonNode> availabilities = findAny(gathered, List.of("availability", "Availability"));
    if (!availabilities.isEmpty()) {
      value.availability = availabilities.get(0).asText();
    }
    if (value.availability != null) {
      String stripped = strip(value.availability, " \"'").toLowerCase(Locale.ROOT);
      value.availability = SCHEMA_PREFIX.matcher(stripped).replaceFirst("");
    }

    // Some vocabularies write their fields as name/content pairs rather than as keys, which a
    // path expression cannot reach; those are searched separately.
    if (value.price == null || value.availability != null) {
      for (JsonNode properties : findAny(gathered, List.of("properties"))) {
        if (value.price == null) {
          String found = searchPropertyByName(properties, "price:amount");
          value.price = found == null ? null : Restock.parseCurrency(found);
        }
        if (value.availability == null) {
          value.availability = searchPropertyByName(properties, "product:availability");
        }
        if (value.currency == null) {
          value.currency = searchPropertyByName(properties, "price:currency");
        }
      }
    }
    return value;
  }

  /** Everything the page states about itself, in one document. */
  static JsonNode gather(String htmlContent) {
    ObjectNode root = PythonJson.MAPPER.createObjectNode();
    Document document = HtmlTools.parseFragmentPreserving(htmlContent);

    ArrayNode linkedData = root.putArray("json-ld");
    for (Element script : document.select("script[type=application/ld+json]")) {
      try {
        linkedData.add(PythonJson.MAPPER.readTree(script.data()));
      } catch (Exception e) {
        // A block that will not parse states nothing.
      }
    }

    ArrayNode microdata = root.putArray("microdata");
    for (Element scope : document.select("[itemscope]")) {
      ObjectNode item = PythonJson.MAPPER.createObjectNode();
      if (scope.hasAttr("itemtype")) {
        item.put("type", scope.attr("itemtype"));
      }
      ObjectNode properties = item.putObject("properties");
      for (Element property : scope.select("[itemprop]")) {
        String name = property.attr("itemprop");
        String value = microdataValue(property);
        if (!name.isEmpty() && value != null) {
          properties.put(name, value);
        }
      }
      if (!properties.isEmpty()) {
        microdata.add(item);
      }
    }

    ArrayNode openGraph = root.putArray("opengraph");
    ObjectNode graphProperties = PythonJson.MAPPER.createObjectNode();
    ArrayNode graphPairs = PythonJson.MAPPER.createArrayNode();
    for (Element meta : document.select("meta[property], meta[name]")) {
      String name = meta.hasAttr("property") ? meta.attr("property") : meta.attr("name");
      String content = meta.attr("content");
      if (!name.isEmpty() && !content.isEmpty()) {
        ArrayNode pair = PythonJson.MAPPER.createArrayNode();
        pair.add(name);
        pair.add(content);
        graphPairs.add(pair);
      }
    }
    if (!graphPairs.isEmpty()) {
      graphProperties.set("properties", graphPairs);
      openGraph.add(graphProperties);
    }

    return root;
  }

  private static String microdataValue(Element element) {
    String name = element.normalName();
    return switch (name) {
      case "meta" -> element.attr("content");
      case "link", "a", "area" -> element.attr("href");
      case "img", "audio", "embed", "iframe", "source", "track", "video" -> element.attr("src");
      case "data", "meter" -> element.attr("value");
      case "time" -> element.hasAttr("datetime") ? element.attr("datetime") : element.text();
      default -> element.hasAttr("content") ? element.attr("content") : element.text();
    };
  }

  /** Every value stored under any of the given keys, at any depth. */
  static List<JsonNode> findAny(JsonNode root, List<String> keys) {
    List<JsonNode> out = new ArrayList<>();
    for (String key : keys) {
      collect(root, key, out);
    }
    return out;
  }

  private static void collect(JsonNode node, String key, List<JsonNode> out) {
    if (node.isObject()) {
      node.fields()
          .forEachRemaining(
              entry -> {
                if (entry.getKey().equals(key)) {
                  out.add(entry.getValue());
                }
                collect(entry.getValue(), key, out);
              });
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        collect(child, key, out);
      }
    }
  }

  private static String searchPropertyByName(JsonNode properties, String wanted) {
    if (properties.isArray()) {
      for (JsonNode pair : properties) {
        if (pair.isArray() && pair.size() >= 2 && pair.get(0).asText().contains(wanted)) {
          return pair.get(1).asText();
        }
      }
    } else if (properties.isObject()) {
      var iterator = properties.fields();
      while (iterator.hasNext()) {
        var entry = iterator.next();
        if (entry.getKey().contains(wanted)) {
          return entry.getValue().asText();
        }
      }
    }
    return null;
  }

  /**
   * The distinct prices the page stated.
   *
   * <p>A page routinely states the same price several ways at once -- as a number, as a string,
   * with a currency symbol -- so they are reduced to numbers before being counted. What is left
   * after that really is more than one price.
   */
  static List<Double> deduplicatePrices(List<JsonNode> values) {
    Set<Double> unique = new LinkedHashSet<>();
    for (JsonNode value : values) {
      if (value.isArray()) {
        for (JsonNode item : value) {
          Double parsed = numeric(item.asText());
          if (parsed != null) {
            unique.add(parsed);
          }
        }
      } else {
        Double parsed = numeric(value.isTextual() ? value.textValue() : value.asText());
        if (parsed != null) {
          unique.add(parsed);
        }
      }
    }
    return new ArrayList<>(unique);
  }

  private static Double numeric(String raw) {
    if (raw == null || raw.strip().isEmpty()) {
      return null;
    }
    String digits = raw.replaceAll("[^\\d.]", "");
    if (digits.isEmpty()) {
      return null;
    }
    try {
      return Double.valueOf(digits);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String strip(String value, String characters) {
    int start = 0;
    int end = value.length();
    while (start < end && characters.indexOf(value.charAt(start)) >= 0) {
      start++;
    }
    while (end > start && characters.indexOf(value.charAt(end - 1)) >= 0) {
      end--;
    }
    return value.substring(start, end);
  }

  /** The linked-data selectors the price tracker adds when the operator accepts it. */
  public static List<String> priceOfferSelectors() {
    return HtmlTools.LD_JSON_PRODUCT_OFFER_SELECTORS;
  }

  static Map<String, Object> asMap(JsonNode node) {
    try {
      return PythonJson.MAPPER.convertValue(
          node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (RuntimeException e) {
      return new LinkedHashMap<>();
    }
  }

  static String unusedFilterEntryPoint(String content, String filter) {
    return JsonFilter.extractJsonAsString(content, filter, "product", false);
  }
}
