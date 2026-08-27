package io.akka.changedetection.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the model's reply.
 *
 * <p>A model asked for JSON routinely wraps it in a code fence or adds a sentence around it, so
 * the object is found inside the reply rather than the reply being parsed whole. A reply that
 * cannot be read at all falls back to the cautious answer rather than to an error, because the
 * evaluation is advisory and a failed read must not stop a check.
 */
public final class ResponseParser {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Selectors that name a position break as soon as the page gains an item. */
  private static final Pattern POSITIONAL_SELECTOR =
      Pattern.compile(
          "nth-child|nth-of-type|:eq\\(|\\[\\d+\\]|//\\*\\[\\d", Pattern.CASE_INSENSITIVE);

  private static final Pattern FENCE_OPEN =
      Pattern.compile("^```(?:json)?[ \\t]*", Pattern.MULTILINE);
  private static final Pattern FENCE_CLOSE = Pattern.compile("[ \\t]*```$", Pattern.MULTILINE);
  private static final Pattern OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

  private ResponseParser() {}

  static String extractJson(String raw) {
    String text = raw.strip();
    text = FENCE_OPEN.matcher(text).replaceAll("");
    text = FENCE_CLOSE.matcher(text).replaceAll("");
    Matcher matcher = OBJECT.matcher(text);
    return matcher.find() ? matcher.group(0) : text;
  }

  public static Map<String, Object> parseEval(String raw) {
    Map<String, Object> result = new LinkedHashMap<>();
    JsonNode data = read(raw);
    if (data == null) {
      result.put("important", false);
      result.put("summary", "");
      return result;
    }
    result.put("important", truthy(data.get("important")));
    result.put("summary", text(data.get("summary")).strip());
    return result;
  }

  public static Map<String, Object> parsePreview(String raw) {
    Map<String, Object> result = new LinkedHashMap<>();
    JsonNode data = read(raw);
    if (data == null) {
      result.put("found", false);
      result.put("answer", "");
      return result;
    }
    result.put("found", truthy(data.get("found")));
    result.put("answer", text(data.get("answer")).strip());
    return result;
  }

  public static Map<String, Object> parseSetup(String raw) {
    Map<String, Object> result = new LinkedHashMap<>();
    JsonNode data = read(raw);
    if (data == null) {
      result.put("needs_prefilter", false);
      result.put("selector", null);
      result.put("reason", "");
      return result;
    }
    boolean needs = truthy(data.get("needs_prefilter"));
    JsonNode selectorNode = data.get("selector");
    String selector =
        selectorNode == null || selectorNode.isNull() || selectorNode.asText("").isEmpty()
            ? null
            : selectorNode.asText();
    if (selector != null && POSITIONAL_SELECTOR.matcher(selector).find()) {
      selector = null;
      needs = false;
    }
    result.put("needs_prefilter", needs);
    result.put("selector", needs ? selector : null);
    result.put("reason", text(data.get("reason")).strip());
    return result;
  }

  private static JsonNode read(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(extractJson(raw));
      return node == null || !node.isObject() ? null : node;
    } catch (Exception e) {
      return null;
    }
  }

  /** Truth as the language the original is written in reads it, not as JSON types alone. */
  private static boolean truthy(JsonNode node) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNumber()) {
      return node.doubleValue() != 0;
    }
    if (node.isTextual()) {
      return !node.textValue().isEmpty();
    }
    if (node.isArray() || node.isObject()) {
      return node.size() > 0;
    }
    return true;
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    return node.isTextual() ? node.textValue() : node.toString();
  }
}
