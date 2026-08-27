package io.akka.changedetection.conditions;

import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.text.PyRegex;
import io.akka.changedetection.text.PythonText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The extra conditions a watch may put on a change before it counts.
 *
 * <p>They are a filter on top of the ordinary decision, not a replacement for it: the checksum
 * still decides whether the page moved, and these decide whether a move is worth reporting.
 * Something like "only when the price is below fifty" or "only when the page changed by more
 * than a fifth" is written here rather than as a text rule.
 *
 * <p>The facts a condition can be written against are gathered before it runs, and each one
 * costs something to work out -- a similarity measure compares against the stored snapshot, a
 * number is parsed out of the page's text. They are gathered whether or not the condition asks
 * for them, which is what the original does.
 */
public final class RuleSet {

  /** What the facts are computed from, beyond the text of this check. */
  public interface Facts {
    /** The text of the newest stored snapshot, for the similarity measures. */
    String newestSnapshot(Watch watch);
  }

  private static final Facts NO_HISTORY = watch -> null;

  private RuleSet() {}

  /** Operator names beyond the rule language's own, added by the original's plugins. */
  public static Map<String, JsonLogic.Operation> customOperations() {
    Map<String, JsonLogic.Operation> operations = new LinkedHashMap<>();
    operations.put("starts_with", (data, arguments) ->
        text(arguments, 0).toLowerCase(Locale.ROOT).strip()
            .startsWith(text(arguments, 1).strip().toLowerCase(Locale.ROOT)));
    operations.put("ends_with", (data, arguments) ->
        text(arguments, 0).toLowerCase(Locale.ROOT).strip()
            .endsWith(text(arguments, 1).strip().toLowerCase(Locale.ROOT)));
    operations.put("length_min", (data, arguments) ->
        text(arguments, 0).length() >= (int) JsonLogic.toNumber(arg(arguments, 1)));
    operations.put("length_max", (data, arguments) ->
        text(arguments, 0).length() <= (int) JsonLogic.toNumber(arg(arguments, 1)));
    operations.put("contains_regex", (data, arguments) ->
        matches(text(arguments, 0), text(arguments, 1)));
    operations.put("!contains_regex", (data, arguments) ->
        !matches(text(arguments, 0), text(arguments, 1)));
    operations.put("!in", (data, arguments) -> {
      Object needle = arg(arguments, 0);
      Object haystack = arg(arguments, 1);
      if (haystack instanceof String text) {
        return !text.contains(JsonLogic.toStringValue(needle));
      }
      if (haystack instanceof List<?> list) {
        for (Object item : list) {
          if (JsonLogic.looseEquals(item, needle)) {
            return false;
          }
        }
      }
      return true;
    });
    return operations;
  }

  private static boolean matches(String text, String pattern) {
    try {
      return PyRegex.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
          .matcher(text)
          .find();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static Object arg(List<Object> arguments, int index) {
    return index < arguments.size() ? arguments.get(index) : null;
  }

  private static String text(List<Object> arguments, int index) {
    return JsonLogic.toStringValue(arg(arguments, index));
  }

  /** The conditions with anything incomplete dropped, as the original drops them. */
  public static List<Map<String, Object>> completeRules(Watch watch) {
    List<Map<String, Object>> complete = new ArrayList<>();
    for (Map<String, Object> rule : watch.fields().maps("conditions")) {
      if (isComplete(rule.get("operator"))
          && isComplete(rule.get("field"))
          && isComplete(rule.get("value"))) {
        complete.add(rule);
      }
    }
    return complete;
  }

  private static boolean isComplete(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    String text = String.valueOf(value);
    return !text.isEmpty() && !text.equals("None");
  }

  /** The conditions turned into one rule in the rule language. */
  public static Object toLogic(String logicOperator, List<Map<String, Object>> rules) {
    List<Object> conditions = new ArrayList<>();
    for (Map<String, Object> rule : rules) {
      String operator = String.valueOf(rule.get("operator"));
      String field = String.valueOf(rule.get("field"));
      Object value = rule.get("value");

      // A value typed into a form arrives as text; where it reads as a number it is used as
      // one, so "greater than 50" compares numerically rather than alphabetically.
      if (value instanceof String s) {
        try {
          value = s.contains(".") ? (Object) Double.valueOf(s) : (Object) Long.valueOf(s);
        } catch (NumberFormatException e) {
          // Left as text.
        }
      }

      Map<String, Object> variable = new LinkedHashMap<>();
      variable.put("var", field);

      Map<String, Object> condition = new LinkedHashMap<>();
      if (operator.equals("in")) {
        condition.put("in", List.of(value, variable));
      } else if (operator.equals("!") || operator.equals("!!") || operator.equals("-")) {
        condition.put(operator, List.of(variable));
      } else if (operator.equals("min") || operator.equals("max") || operator.equals("cat")) {
        condition.put(operator, value);
      } else {
        List<Object> pair = new ArrayList<>();
        pair.add(variable);
        pair.add(value);
        condition.put(operator, pair);
      }
      conditions.add(condition);
    }
    if (conditions.size() == 1) {
      return conditions.get(0);
    }
    Map<String, Object> joined = new LinkedHashMap<>();
    joined.put(logicOperator, conditions);
    return joined;
  }

  /** True when the watch has no conditions, or when the ones it has are met. */
  public static boolean evaluate(Watch watch, String text) {
    return evaluate(watch, text, NO_HISTORY);
  }

  public static boolean evaluate(Watch watch, String text, Facts facts) {
    List<Map<String, Object>> rules = completeRules(watch);
    if (rules.isEmpty()) {
      return true;
    }
    String matchLogic = watch.fields().string("conditions_match_logic",
        Fields.CONDITIONS_MATCH_LOGIC_DEFAULT);
    String logicOperator = "ALL".equals(matchLogic) ? "and" : "or";

    Map<String, Object> data = gatherFacts(watch, text, facts);
    Map<String, JsonLogic.Operation> operations = JsonLogic.builtins();
    operations.putAll(customOperations());

    // A rule that names an operator or a fact nothing supplies is not quietly treated as
    // holding or as failing: it stops the check and is reported against the watch, because
    // either silent answer would leave an operator with a rule that does nothing and no sign
    // of it. The rule is theirs to correct, so the error is one they can act on.
    Object result = new JsonLogic(operations).apply(toLogic(logicOperator, rules), data);
    return JsonLogic.toBool(result);
  }

  /** Everything a condition may be written against. */
  public static Map<String, Object> gatherFacts(Watch watch, String text, Facts facts) {
    // A caller with no history to offer passes nothing; the facts drawn from history are then
    // simply absent, which is what a watch on its first check has anyway.
    Facts history = facts == null ? NO_HISTORY : facts;
    Map<String, Object> data = new LinkedHashMap<>();
    if (text == null) {
      return data;
    }
    data.put("page_filtered_text", text);

    Double price = PriceParser.parse(text);
    if (price != null) {
      data.put("extracted_number", price);
    }

    // Named as the plugin that supplies it names it. The count is of the text arriving, not
    // of anything stored, so it is available on a watch's first check as well as later ones.
    data.put("word_count", (long) PythonText.splitOnWhitespace(text).size());

    String previous = history.newestSnapshot(watch);
    if (previous != null) {
      int distance = Levenshtein.distance(previous, text);
      double ratio = Levenshtein.ratio(previous, text);
      data.put("levenshtein_distance", (long) distance);
      data.put("levenshtein_ratio", ratio);
      data.put("levenshtein_similarity", Math.round(ratio * 100 * 100) / 100.0);
    }
    return data;
  }
}
