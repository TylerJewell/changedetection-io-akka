package io.akka.changedetection.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Version;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The three JSON filter dialects a watch may use, and the search for JSON inside a page.
 *
 * <p>The dangerous jq builtins are refused rather than sandboxed. A jq expression is supplied
 * by whoever configures the watch and runs on the server, and {@code env} alone would return
 * every environment variable the process holds as the watch's content.
 */
public final class JsonFilter {

  public static final List<String> JSON_FILTER_PREFIXES = List.of("json:", "jq:", "jqraw:");

  private static final List<String[]> BLOCKED_JQ = List.of(
      new String[] {"\\benv\\b", "env (reads environment variables)"},
      new String[] {"\\$ENV\\b", "$ENV (reads environment variables)"},
      new String[] {"\\binclude\\b", "include (reads files from disk)"},
      new String[] {"\\bimport\\b", "import (reads files from disk)"},
      new String[] {"\\binputs?\\b", "input/inputs (reads beyond provided data)"},
      new String[] {"\\bdebug\\b", "debug (leaks data to stderr)"},
      new String[] {"\\bstderr\\b", "stderr (leaks data to stderr)"},
      new String[] {"\\bhalt(?:_error)?\\b", "halt/halt_error (terminates the process)"},
      new String[] {"\\$__loc__\\b", "$__loc__ (leaks file path information)"},
      new String[] {"\\bbuiltins\\b", "builtins (enumerates available functions)"},
      new String[] {"\\bmodulemeta\\b", "modulemeta (leaks module information)"},
      new String[] {
        "\\$JQ_BUILD_CONFIGURATION\\b", "$JQ_BUILD_CONFIGURATION (leaks build information)"
      });

  private static final Pattern JSONP =
      Pattern.compile("^\\w[\\w.]*\\s*\\((.+)\\)\\s*;?\\s*$", Pattern.DOTALL);
  private static final Pattern LONE_SURROGATE = Pattern.compile("[\\uD800-\\uDFFF]");

  private static final Configuration JSONPATH_CONFIG =
      Configuration.builder()
          .jsonProvider(new JacksonJsonNodeJsonProvider(PythonJson.MAPPER))
          .mappingProvider(new JacksonMappingProvider(PythonJson.MAPPER))
          .options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
          .build();

  private static final Scope JQ_SCOPE = newJqScope();

  private JsonFilter() {}

  private static Scope newJqScope() {
    Scope scope = Scope.newEmptyScope();
    BuiltinFunctionLoader.getInstance().loadFunctions(Version.LATEST, scope);
    return scope;
  }

  /** Raised where the original raises on a jq expression it will not run. */
  public static class BlockedJqExpression extends RuntimeException {
    public BlockedJqExpression(String message) {
      super(message);
    }
  }

  public static void validateJqExpression(String expression, boolean allowRisky) {
    if (allowRisky) {
      return;
    }
    for (String[] blocked : BLOCKED_JQ) {
      if (Pattern.compile(blocked[0]).matcher(expression).find()) {
        throw new BlockedJqExpression("jq expression uses disallowed builtin: " + blocked[1]);
      }
    }
  }

  /**
   * The filtered document as the text the rules see: several matches come back as a JSON list,
   * one match as the value itself, and no match as the empty string rather than an error.
   */
  public static String parseJson(JsonNode data, String jsonFilter, boolean allowRiskyJq) {
    JsonNode sanitised = sanitiseLoneSurrogates(data);

    if (jsonFilter.startsWith("json:")) {
      String path = jsonFilter.replace("json:", "");
      List<JsonNode> matches = jsonPathFind(sanitised, path);
      return strippedTextFromMatches(matches);
    }
    if (jsonFilter.startsWith("jq:")) {
      String expr = jsonFilter.substring("jq:".length());
      validateJqExpression(expr, allowRiskyJq);
      return strippedTextFromMatches(runJq(sanitised, expr));
    }
    if (jsonFilter.startsWith("jqraw:")) {
      String expr = jsonFilter.substring("jqraw:".length());
      validateJqExpression(expr, allowRiskyJq);
      List<String> parts = new ArrayList<>();
      for (JsonNode item : runJq(sanitised, expr)) {
        parts.add(item.isTextual() ? item.textValue() : PythonJson.dumpsSortedCompact(item));
      }
      return String.join("\n", parts);
    }
    return null;
  }

  /** JSONPath, with the alternation form the restock extractor relies on handled directly. */
  public static List<JsonNode> jsonPathFind(JsonNode data, String path) {
    List<String> alternatives = expandAlternation(path);
    List<JsonNode> out = new ArrayList<>();
    for (String p : alternatives) {
      try {
        JsonNode result = JsonPath.using(JSONPATH_CONFIG).parse(data).read(p);
        if (result != null && result.isArray()) {
          for (JsonNode n : result) {
            out.add(n);
          }
        } else if (result != null && !result.isMissingNode()) {
          out.add(result);
        }
      } catch (Exception e) {
        // A path that does not resolve contributes nothing, as in the original.
      }
    }
    return out;
  }

  /**
   * {@code $..(price|Price)} is an alternation the original's JSONPath library understands and
   * this one does not, so it is expanded into one path per branch, in the order written.
   */
  private static List<String> expandAlternation(String path) {
    Matcher m = Pattern.compile("\\(([^()]*\\|[^()]*)\\)").matcher(path);
    if (!m.find()) {
      return List.of(path);
    }
    List<String> out = new ArrayList<>();
    for (String branch : m.group(1).split("\\|")) {
      out.add(path.substring(0, m.start()) + branch.trim() + path.substring(m.end()));
    }
    return out;
  }

  private static List<JsonNode> runJq(JsonNode data, String expr) {
    try {
      JsonQuery query = JsonQuery.compile(expr, Version.LATEST);
      List<JsonNode> out = new ArrayList<>();
      query.apply(Scope.newChildScope(JQ_SCOPE), data, out::add);
      return out;
    } catch (BlockedJqExpression e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("jq expression failed: " + e.getMessage(), e);
    }
  }

  private static String strippedTextFromMatches(List<JsonNode> matches) {
    if (matches.isEmpty()) {
      return "";
    }
    if (matches.size() == 1) {
      return PythonJson.dumpsIndented(matches.get(0));
    }
    ArrayNode array = PythonJson.MAPPER.createArrayNode();
    for (JsonNode n : matches) {
      array.add(n);
    }
    return PythonJson.dumpsIndented(array);
  }

  /**
   * The filter applied to a document that may be JSON, may be JSON wrapped in a callback, or
   * may be a web page with JSON buried in it.
   */
  public static String extractJsonAsString(
      String content, String jsonFilter, String ensureIsLdJsonInfoType, boolean allowRiskyJq) {
    String stripped = null;
    String contentStart = stripBom(content).strip();
    String head = contentStart.length() > 100 ? contentStart.substring(0, 100) : contentStart;

    if (!head.isEmpty() && (head.charAt(0) == '{' || head.charAt(0) == '[')) {
      try {
        stripped = parseJson(PythonJson.MAPPER.readTree(stripBom(content)), jsonFilter, allowRiskyJq);
      } catch (com.fasterxml.jackson.core.JacksonException e) {
        stripped = null;
      }
    } else {
      Matcher jsonp = JSONP.matcher(contentStart);
      if (jsonp.matches()) {
        try {
          stripped =
              parseJson(PythonJson.MAPPER.readTree(jsonp.group(1).strip()), jsonFilter, allowRiskyJq);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
          stripped = null;
        }
      }
      if (stripped == null || stripped.isEmpty()) {
        stripped = extractJsonBlobFromHtml(content, ensureIsLdJsonInfoType, jsonFilter, allowRiskyJq);
      }
    }

    return stripped == null ? "" : stripped;
  }

  private static String stripBom(String s) {
    int at = 0;
    while (at < s.length() && s.charAt(at) == '\uFEFF') {
      at++;
    }
    return at == 0 ? s : s.substring(at);
  }

  /**
   * Every script block and the body itself tried in turn, taking the first that both parses and
   * answers the filter. Where a linked-data type is demanded, only a block declaring that type
   * counts.
   */
  public static String extractJsonBlobFromHtml(
      String content, String ensureIsLdJsonInfoType, String jsonFilter, boolean allowRiskyJq) {
    Document soup = HtmlTools.parseFragmentPreserving(content);
    List<Element> candidates = new ArrayList<>();
    if (ensureIsLdJsonInfoType != null) {
      candidates.addAll(soup.select("script[type=application/ld+json]"));
    } else {
      candidates.addAll(soup.select("script"));
    }
    candidates.addAll(soup.select("body"));

    List<JsonNode> parsed = new ArrayList<>();
    for (Element element : candidates) {
      String text = element.normalName().equals("script") ? element.data() : element.text();
      String start = stripBom(text == null ? "" : text).strip();
      if (start.isEmpty() || !(start.charAt(0) == '{' || start.charAt(0) == '[')) {
        continue;
      }
      try {
        parsed.add(PythonJson.MAPPER.readTree(text));
      } catch (Exception e) {
        // A block that will not parse is skipped, as in the original.
      }
    }

    if (parsed.isEmpty()) {
      throw new HtmlTools.JsonNotFound("No parsable JSON found in this document");
    }

    String strippedTextFromHtml = "";
    for (JsonNode jsonData : parsed) {
      strippedTextFromHtml = parseJson(jsonData, jsonFilter, allowRiskyJq);
      if (strippedTextFromHtml == null) {
        strippedTextFromHtml = "";
      }
      if (ensureIsLdJsonInfoType != null) {
        if (jsonData.isObject()) {
          JsonNode t = jsonData.get("@type");
          if (t != null && !strippedTextFromHtml.isEmpty()) {
            if (t.isTextual()
                && t.textValue().toLowerCase(Locale.ROOT)
                    .equals(ensureIsLdJsonInfoType.toLowerCase(Locale.ROOT))) {
              break;
            }
            if (t.isArray()) {
              for (JsonNode entry : t) {
                if (entry.isTextual()
                    && entry.textValue().strip().toLowerCase(Locale.ROOT)
                        .equals(ensureIsLdJsonInfoType.toLowerCase(Locale.ROOT))) {
                  return strippedTextFromHtml;
                }
              }
            }
          }
        }
      } else if (!strippedTextFromHtml.isEmpty()) {
        break;
      }
    }
    return strippedTextFromHtml;
  }

  private static JsonNode sanitiseLoneSurrogates(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isTextual()) {
      String value = node.textValue();
      Matcher m = LONE_SURROGATE.matcher(value);
      if (!m.find()) {
        return node;
      }
      return new TextNode(m.replaceAll("�"));
    }
    if (node.isObject()) {
      com.fasterxml.jackson.databind.node.ObjectNode out = PythonJson.MAPPER.createObjectNode();
      node.fields()
          .forEachRemaining(e -> out.set(sanitiseKey(e.getKey()), sanitiseLoneSurrogates(e.getValue())));
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = PythonJson.MAPPER.createArrayNode();
      for (JsonNode child : node) {
        out.add(sanitiseLoneSurrogates(child));
      }
      return out;
    }
    return node;
  }

  private static String sanitiseKey(String key) {
    return LONE_SURROGATE.matcher(key).replaceAll("�");
  }

  /** Compiles a JSONPath and throws if it will not compile. */
  public static void validateJsonPath(String path) {
    for (String alternative : expandAlternation(path.strip())) {
      JsonPath.compile(alternative);
    }
  }

  /** Compiles a jq programme, having first refused the builtins that reach outside it. */
  public static void validateJqProgramme(String expression) {
    validateJqExpression(expression, false);
    try {
      JsonQuery.compile(expression.strip(), Version.LATEST);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e.getMessage() == null ? "invalid" : e.getMessage());
    }
  }
}
