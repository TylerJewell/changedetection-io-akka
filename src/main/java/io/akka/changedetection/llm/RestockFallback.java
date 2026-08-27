package io.akka.changedetection.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.LlmSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * The last resort for reading a price off a page.
 *
 * <p>Reached only when the structured data a shop is supposed to publish is missing or
 * incomplete. The page is stripped down to what a person would read, the structured blocks that
 * do mention a price are put in front of it, and a model is asked for the one product's price
 * and availability.
 */
public final class RestockFallback {

  static final String SYSTEM_PROMPT =
      "You are an expert price and restock extraction utility. "
          + "Your task is to analyse a product page and determine the price and stock status of"
          + " the MAIN product only.\n\n"
          + "AVAILABILITY — treat as \"in stock\":\n"
          + "- Action buttons near the product: \"Add to cart\", \"Add to basket\", \"Buy now\","
          + " \"Order now\", \"Purchase\", \"Import\", \"Add to bag\", \"Add to trolley\", \"In"
          + " stock\", \"Available\", \"Ships in X days/weeks\", \"In store\", \"Pick up"
          + " today\".\n"
          + "- \"Pre-order\" or \"Reserve\" — the item is orderable, treat as \"in stock\".\n"
          + "- \"Only X left\", \"Almost gone\", \"Low stock\", \"Limited availability\" — still"
          + " in stock.\n"
          + "- \"Request a quote\" or \"Contact us for pricing\" — item is available, price is"
          + " null.\n"
          + "- IMPORTANT: Ignore cart/basket/bag links in the page HEADER or navigation bar"
          + " (e.g. a shopping cart icon showing item count). That reflects what is already in"
          + " the visitor's cart — it says nothing about whether THIS product is available.\n\n"
          + "PRICE — what NOT to use:\n"
          + "- A \"$0.00\" or \"0\" that appears near header/nav links such as \"Login\","
          + " \"Wishlist\", \"Contact Us\", \"My Account\" is an empty shopping-cart indicator,"
          + " NOT the product price. Ignore it entirely — return null for price rather than 0 in"
          + " this situation.\n"
          + "- Only return 0 (free) when the page clearly states the product itself costs"
          + " nothing (e.g. \"Free\", \"Free download\", \"Price: $0\").\n\n"
          + "AVAILABILITY — treat as \"out of stock\":\n"
          + "- \"Out of stock\", \"Sold out\", \"Unavailable\", \"Currently unavailable\","
          + " \"Temporarily out of stock\", \"Discontinued\", \"No longer available\", \"Notify"
          + " me when available\", \"Email me when back\", \"Join waitlist\".\n\n"
          + "AVAILABILITY — return null when uncertain:\n"
          + "- The page asks the user to select a size, colour, or other variant first"
          + " (\"Select an option\", \"Choose a size\") — availability depends on the variant, so"
          + " return null.\n"
          + "- You cannot clearly tell from the page content whether the item is available.\n\n"
          + "PRICE rules:\n"
          + "- Extract the main selling price as a plain number, no currency symbol.\n"
          + "- Prices may use any popular locale format — interpret them all correctly and"
          + " return a plain decimal number. Examples: \"10 000 Kč\" = 10000, \"1.299,95 €\" ="
          + " 1299.95, \"1,299.95\" = 1299.95, \"10 000,50\" = 10000.50, \"£1.299\" = 1299,"
          + " \"¥10000\" = 10000.\n"
          + "- If both an original (crossed-out) price and a sale/current price appear, use the"
          + " sale price.\n"
          + "- \"From $X\" or \"Starting at $X\" are teaser prices — prefer a definite price or"
          + " return null.\n"
          + "- A price of 0 (free) is valid — return 0, not null.\n"
          + "- If pricing requires a quote or login, return null for price.\n"
          + "- Ignore prices shown in search/filter UI elements (e.g. \"Price from: — to:\").\n"
          + "- IMPORTANT: Ignore ALL prices that appear inside or below recommendation/discovery"
          + " blocks such as: \"Similar items\", \"You may also like\", \"Customers also"
          + " bought\", \"Based on your browsing\", \"Based on your shopping\", \"Frequently"
          + " bought together\", \"People also viewed\", \"Related products\", \"Sponsored"
          + " products\", \"More like this\", \"Other sellers\", \"Compare with similar items\"."
          + " These sections contain prices for OTHER products, not the main product.\n"
          + "- When multiple prices appear on the page, prefer the price that is positioned"
          + " earliest/highest in the page content — it is almost always the main product price."
          + " Prices appearing after large blocks of descriptive text or review sections are"
          + " likely from recommendation widgets and should be ignored.\n\n"
          + "CLASSIFIEDS AND LISTING PAGES:\n"
          + "- On classifieds or marketplace sites (e.g. eBay listings, Craigslist, Bazoš,"
          + " Gumtree), if a price is shown alongside seller contact details or a \"Contact"
          + " seller\" link, treat the item as \"instock\" — the listing being active means it is"
          + " available.\n\n"
          + "Return ONLY a JSON object with exactly these three keys:\n"
          + "  \"price\"        — number or null\n"
          + "  \"currency\"     — ISO-4217 code (USD, EUR, GBP …) or null\n"
          + "  \"availability\" — exactly one of: \"instock\", \"outofstock\", or null\n"
          + "                   Use \"instock\" when the product can be ordered/purchased.\n"
          + "                   Use \"outofstock\" when it cannot.\n"
          + "                   Use null when you genuinely cannot tell.\n"
          + "No markdown, no backticks, no explanation — pure JSON only.";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Pattern JSONLD_BLOCK =
      Pattern.compile(
          "<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  private static final Pattern JSONLD_RELEVANT =
      Pattern.compile(
          "\"(price|priceCurrency|lowPrice|highPrice|availability|offers|InStock|OutOfStock"
              + "|priceSpecification)\"",
          Pattern.CASE_INSENSITIVE);

  /** Sections that are the site around the product rather than the product. */
  private static final List<String> CHROME_TAGS = List.of("nav", "header", "footer", "aside");

  private static final Pattern CHROME_NAMES =
      Pattern.compile(
          "\\b(nav|navigation|navbar|menu|mega-menu|breadcrumb|breadcrumbs?|"
              + "site-header|page-header|top-bar|top-nav|top-header|mobile-nav|header-bar|"
              + "site-footer|page-footer|footer-links|related|similar|"
              + "you-?may-?also|customers?-?also|frequently-?bought|"
              + "people-?also|sponsored|recommendation|widget|sidebar|"
              + "cross-?sell|up-?sell)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern COMMENTS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
  private static final Pattern SCRIPT_OR_STYLE =
      Pattern.compile(
          "<(script|style)[^>]*>.*?</(script|style)>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern TAGS = Pattern.compile("<[^>]+>");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern NOT_A_NUMBER = Pattern.compile("[^\\d.]");
  private static final Pattern FENCE_OPEN = Pattern.compile("^```[a-z]*\\n?");

  /**
   * The last answer, kept so an unchanged page is not paid for twice.
   *
   * <p>A product page changes its raw markup on every fetch -- a nonce, an analytics token --
   * so the check's own "nothing changed" shortcut almost never fires for one. Keying on what
   * would actually be sent means tokens are spent only when the readable content moves.
   */
  private static final LinkedHashMap<String, Map<String, Object>> CACHE =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
          return size() > 500;
        }
      };

  private RestockFallback() {}

  static int maxContentChars() {
    String configured = System.getenv("LLM_RESTOCK_MAX_CONTENT_CHARS");
    if (configured == null || configured.isBlank()) {
      return 15_000;
    }
    try {
      return Integer.parseInt(configured.strip());
    } catch (NumberFormatException e) {
      return 15_000;
    }
  }

  static String extractJsonLd(String htmlContent) {
    Matcher matcher = JSONLD_BLOCK.matcher(htmlContent);
    StringBuilder combined = new StringBuilder();
    boolean any = false;
    while (matcher.find()) {
      any = true;
      String block = matcher.group(1).strip();
      if (JSONLD_RELEVANT.matcher(block).find()) {
        if (combined.length() > 0) {
          combined.append(' ');
        }
        combined.append(block);
      }
    }
    if (!any) {
      return "";
    }
    String text = combined.toString();
    return text.length() > 2000 ? text.substring(0, 2000) : text;
  }

  static String removeChrome(String htmlContent) {
    try {
      Document document = Jsoup.parse(htmlContent, "", Parser.htmlParser());
      for (Element element : document.getAllElements().toArray(new Element[0])) {
        if (element.parent() == null) {
          continue;
        }
        String name = element.tagName();
        if (CHROME_TAGS.contains(name)) {
          element.remove();
          continue;
        }
        String names = element.className() + " " + element.id();
        if (CHROME_NAMES.matcher(names).find()) {
          element.remove();
        }
      }
      return document.outerHtml();
    } catch (RuntimeException e) {
      return htmlContent;
    }
  }

  static String stripHtml(String htmlContent, int maxChars) {
    String jsonld = extractJsonLd(htmlContent);
    String cleaned = removeChrome(htmlContent);
    String text = COMMENTS.matcher(cleaned).replaceAll(" ");
    text = SCRIPT_OR_STYLE.matcher(text).replaceAll(" ");
    text = TAGS.matcher(text).replaceAll(" ");
    text =
        text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    text = WHITESPACE.matcher(text).replaceAll(" ").strip();

    if (!jsonld.isEmpty()) {
      // The structured block is extra context, not a replacement: the visible price is often
      // not in it at all, so it may take at most half the budget.
      int cap = Math.max(1, maxChars / 2);
      String head = jsonld.length() > cap ? jsonld.substring(0, cap) : jsonld;
      int budget = maxChars - head.length() - 1;
      String tail = text.length() > Math.max(0, budget) ? text.substring(0, Math.max(0, budget)) : text;
      return (head + " " + tail).strip();
    }
    return text.length() > maxChars ? text.substring(0, maxChars) : text;
  }

  /** What the page says the price and availability are, or null when the model cannot tell. */
  public static Map<String, Object> extract(
      Evaluator.Surroundings around, String content, String url, String llmIntent) {
    if (around == null) {
      return null;
    }
    Map<String, Object> llm = LlmSettings.of(around.application());
    if (!Fields.truthy(llm.get("restock_use_fallback_extract"))) {
      return null;
    }
    if (!Fields.truthy(llm.get("enabled")) || Evaluator.featuresDisabledByEnvironment()) {
      return null;
    }
    Map<String, Object> config = Evaluator.config(around);
    if (config == null || String.valueOf(config.getOrDefault("model", "")).isEmpty()) {
      return null;
    }

    String text = content == null ? "" : stripHtml(content, Evaluator.maxInputChars(around));
    if (text.strip().isEmpty()) {
      return null;
    }

    StringBuilder userPrompt = new StringBuilder();
    userPrompt
        .append("URL: ")
        .append(url == null || url.isEmpty() ? "unknown" : url)
        .append("\n\nPage content:\n")
        .append(text);
    if (llmIntent != null && !llmIntent.isEmpty()) {
      userPrompt.append("\n\nUser notification intent: ").append(llmIntent);
    }

    String model = String.valueOf(config.getOrDefault("model", ""));
    String cacheKey = Evaluator.hexDigest("MD5", model + "\n" + userPrompt);
    synchronized (CACHE) {
      Map<String, Object> cached = CACHE.get(cacheKey);
      if (cached != null) {
        Map<String, Object> reused = new LinkedHashMap<>(cached);
        reused.put("_tokens", 0);
        reused.put("_input_tokens", 0);
        reused.put("_output_tokens", 0);
        reused.put("_model", model);
        return reused;
      }
    }

    int thinkingBudget =
        (int)
            asLong(
                llm.getOrDefault("thinking_budget", LlmSettings.DEFAULT_THINKING_BUDGET),
                LlmSettings.DEFAULT_THINKING_BUDGET);

    LlmClient.Request request = new LlmClient.Request();
    request.model = model;
    request.messages =
        List.of(
            new LlmClient.Message("system", SYSTEM_PROMPT),
            new LlmClient.Message("user", userPrompt.toString()));
    request.apiKey = blankIfNull(config.get("api_key"));
    request.apiBase = blankIfNull(config.get("api_base"));
    request.timeoutSeconds = Evaluator.resolveTimeout(config);
    // The budget has to cover the model's working as well as the answer, because a model that
    // reasons counts that against the same cap and would otherwise stop mid-object.
    request.maxTokens =
        Evaluator.applyLocalTokenMultiplier(Math.max(1000, thinkingBudget + 800), config);
    request.extraBody = LlmClient.thinkingBudget(model, thinkingBudget);
    request.debug = Fields.truthy(llm.get("debug"));

    LlmClient.Completion completion;
    try {
      completion = LlmClient.completion(request);
    } catch (RuntimeException e) {
      return null;
    }

    Evaluator.accumulate(
        around,
        completion.totalTokens(),
        completion.inputTokens(),
        completion.outputTokens(),
        model);

    String raw = completion.text().strip();
    if (raw.startsWith("```")) {
      raw = FENCE_OPEN.matcher(raw).replaceFirst("");
      while (raw.endsWith("`")) {
        raw = raw.substring(0, raw.length() - 1);
      }
      raw = raw.strip();
    }

    JsonNode parsed;
    try {
      parsed = MAPPER.readTree(raw);
    } catch (Exception e) {
      return null;
    }
    if (parsed == null || !parsed.isObject()) {
      return null;
    }

    Double price = null;
    JsonNode priceNode = parsed.get("price");
    if (priceNode != null && !priceNode.isNull()) {
      try {
        price =
            priceNode.isTextual()
                ? Double.parseDouble(NOT_A_NUMBER.matcher(priceNode.textValue()).replaceAll(""))
                : priceNode.asDouble();
      } catch (NumberFormatException e) {
        price = null;
      }
    }
    String currency = textOrNull(parsed.get("currency"));
    String availability = textOrNull(parsed.get("availability"));

    if (price == null && (availability == null || availability.isEmpty())) {
      return null;
    }

    Map<String, Object> clean = new LinkedHashMap<>();
    clean.put("price", price);
    clean.put("currency", currency);
    clean.put("availability", availability);
    synchronized (CACHE) {
      CACHE.put(cacheKey, new LinkedHashMap<>(clean));
    }

    Map<String, Object> result = new LinkedHashMap<>(clean);
    result.put("_tokens", completion.totalTokens());
    result.put("_input_tokens", completion.inputTokens());
    result.put("_output_tokens", completion.outputTokens());
    result.put("_model", model);
    return result;
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String value = node.isTextual() ? node.textValue() : node.asText("");
    return value.isEmpty() ? null : value;
  }

  private static String blankIfNull(Object value) {
    String text = value == null ? "" : String.valueOf(value);
    return text.equals("null") ? "" : text;
  }

  private static long asLong(Object value, long fallback) {
    return value instanceof Number number ? number.longValue() : fallback;
  }
}
