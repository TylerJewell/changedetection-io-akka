package io.akka.changedetection.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a provider charges, in dollars per million tokens.
 *
 * <p>Prices change without notice and a wrong figure in a running total is worse than none, so
 * a model that is not listed contributes nothing to the total rather than a guess. An operator
 * who wants their own prices -- a negotiated rate, a model released since -- puts a
 * {@code llm-pricing.json} of {@code {"model": [inputPerMillion, outputPerMillion]}} on the
 * classpath, and it wins over the built-in table.
 */
final class Pricing {

  private static final Map<String, double[]> TABLE = load();

  private Pricing() {}

  /** The input and output rate, or null when the model is not priced. */
  static double[] perMillionTokens(String model) {
    String name = LlmClient.bareModel(model).toLowerCase(Locale.ROOT);
    double[] exact = TABLE.get(name);
    if (exact != null) {
      return exact;
    }
    // A dated release is priced as its family -- "claude-3-5-haiku-20251001" as
    // "claude-3-5-haiku" -- because a provider prices the family, not the day.
    double[] best = null;
    int bestLength = -1;
    for (Map.Entry<String, double[]> entry : TABLE.entrySet()) {
      if (name.startsWith(entry.getKey()) && entry.getKey().length() > bestLength) {
        best = entry.getValue();
        bestLength = entry.getKey().length();
      }
    }
    return best;
  }

  private static Map<String, double[]> load() {
    Map<String, double[]> table = builtIn();
    try (InputStream stream = Pricing.class.getResourceAsStream("/llm-pricing.json")) {
      if (stream == null) {
        return table;
      }
      JsonNode root =
          new ObjectMapper().readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      root.fields()
          .forEachRemaining(
              entry -> {
                JsonNode rate = entry.getValue();
                if (rate.isArray() && rate.size() == 2) {
                  table.put(
                      entry.getKey().toLowerCase(Locale.ROOT),
                      new double[] {rate.get(0).asDouble(), rate.get(1).asDouble()});
                }
              });
    } catch (Exception e) {
      // A table that will not load leaves the built-in one in place; cost is advisory.
    }
    return table;
  }

  private static Map<String, double[]> builtIn() {
    Map<String, double[]> table = new LinkedHashMap<>();
    table.put("gpt-4o-mini", new double[] {0.15, 0.60});
    table.put("gpt-4o", new double[] {2.50, 10.00});
    table.put("gpt-4.1-mini", new double[] {0.40, 1.60});
    table.put("gpt-4.1-nano", new double[] {0.10, 0.40});
    table.put("gpt-4.1", new double[] {2.00, 8.00});
    table.put("o3-mini", new double[] {1.10, 4.40});
    table.put("claude-3-5-haiku", new double[] {0.80, 4.00});
    table.put("claude-3-5-sonnet", new double[] {3.00, 15.00});
    table.put("claude-3-haiku", new double[] {0.25, 1.25});
    table.put("claude-3-opus", new double[] {15.00, 75.00});
    table.put("gemini-1.5-flash", new double[] {0.075, 0.30});
    table.put("gemini-1.5-pro", new double[] {1.25, 5.00});
    table.put("gemini-2.0-flash", new double[] {0.10, 0.40});
    return table;
  }
}
