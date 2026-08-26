package io.akka.changedetection.conditions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rule language a watch's conditions are compiled into.
 *
 * <p>Its comparisons are deliberately loose -- a number written as text compares equal to the
 * number -- because the values being compared come from a page and arrive as text. A strict
 * comparison would make "price greater than 100" never true.
 */
public final class JsonLogic {

  /** One operator, given the data being tested and its already-evaluated arguments. */
  public interface Operation {
    Object apply(Object data, List<Object> arguments);
  }

  private final Map<String, Operation> operations;

  public JsonLogic(Map<String, Operation> operations) {
    this.operations = operations;
  }

  public static JsonLogic standard() {
    return new JsonLogic(builtins());
  }

  public Object apply(Object logic, Object data) {
    if (logic instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object item : list) {
        out.add(apply(item, data));
      }
      return out;
    }
    if (!(logic instanceof Map<?, ?> map) || map.size() != 1) {
      return logic;
    }
    String operator = String.valueOf(map.keySet().iterator().next());
    Object rawArguments = map.values().iterator().next();
    List<Object> arguments =
        rawArguments instanceof List<?> list ? new ArrayList<>(list) : listOf(rawArguments);

    switch (operator) {
      case "if", "?:" -> {
        int index = 0;
        while (index < arguments.size() - 1) {
          if (toBool(apply(arguments.get(index), data))) {
            return apply(arguments.get(index + 1), data);
          }
          index += 2;
        }
        return index >= arguments.size() ? null : apply(arguments.get(index), data);
      }
      case "and" -> {
        Object current = null;
        for (Object argument : arguments) {
          current = apply(argument, data);
          if (!toBool(current)) {
            return current;
          }
        }
        return current;
      }
      case "or" -> {
        Object current = null;
        for (Object argument : arguments) {
          current = apply(argument, data);
          if (toBool(current)) {
            return current;
          }
        }
        return current;
      }
      default -> {
        // Every other operator has its arguments evaluated before it runs.
      }
    }

    List<Object> evaluated = new ArrayList<>();
    for (Object argument : arguments) {
      evaluated.add(apply(argument, data));
    }
    Operation operation = operations.get(operator);
    if (operation == null) {
      throw new IllegalArgumentException("unknown condition operator '" + operator + "'");
    }
    return operation.apply(data, evaluated);
  }

  private static List<Object> listOf(Object value) {
    List<Object> out = new ArrayList<>();
    out.add(value);
    return out;
  }

  public static Map<String, Operation> builtins() {
    Map<String, Operation> operations = new LinkedHashMap<>();
    operations.put("var", (data, arguments) -> {
      Object name = arguments.isEmpty() ? null : arguments.get(0);
      Object fallback = arguments.size() > 1 ? arguments.get(1) : null;
      if (name == null || "".equals(name)) {
        return data;
      }
      Object current = data;
      for (String part : String.valueOf(name).split("\\.")) {
        if (current instanceof Map<?, ?> map) {
          current = map.get(part);
        } else if (current instanceof List<?> list) {
          try {
            int index = Integer.parseInt(part);
            current = index >= 0 && index < list.size() ? list.get(index) : null;
          } catch (NumberFormatException e) {
            return fallback;
          }
        } else {
          return fallback;
        }
        if (current == null) {
          return fallback;
        }
      }
      return current;
    });
    operations.put("missing", (data, arguments) -> {
      List<Object> missing = new ArrayList<>();
      for (Object name : flatten(arguments)) {
        Object value = operations.get("var").apply(data, listOf(name));
        if (value == null || "".equals(value)) {
          missing.add(name);
        }
      }
      return missing;
    });
    operations.put("==", (data, arguments) -> looseEquals(arg(arguments, 0), arg(arguments, 1)));
    operations.put("!=", (data, arguments) -> !looseEquals(arg(arguments, 0), arg(arguments, 1)));
    operations.put("===", (data, arguments) -> strictEquals(arg(arguments, 0), arg(arguments, 1)));
    operations.put("!==", (data, arguments) -> !strictEquals(arg(arguments, 0), arg(arguments, 1)));
    operations.put("!", (data, arguments) -> !toBool(arg(arguments, 0)));
    operations.put("!!", (data, arguments) -> toBool(arg(arguments, 0)));
    operations.put(">", (data, arguments) -> greaterThan(arg(arguments, 0), arg(arguments, 1)));
    operations.put(">=", (data, arguments) ->
        greaterThan(arg(arguments, 0), arg(arguments, 1))
            || looseEquals(arg(arguments, 0), arg(arguments, 1)));
    operations.put("<", (data, arguments) -> arguments.size() > 2
        ? lessThan(arg(arguments, 0), arg(arguments, 1))
            && lessThan(arg(arguments, 1), arg(arguments, 2))
        : lessThan(arg(arguments, 0), arg(arguments, 1)));
    operations.put("<=", (data, arguments) ->
        lessThan(arg(arguments, 0), arg(arguments, 1))
            || looseEquals(arg(arguments, 0), arg(arguments, 1)));
    operations.put("+", (data, arguments) -> {
      double total = 0;
      for (Object argument : arguments) {
        total += toNumber(argument);
      }
      return narrow(total);
    });
    operations.put("-", (data, arguments) -> arguments.size() == 1
        ? narrow(-toNumber(arg(arguments, 0)))
        : narrow(toNumber(arg(arguments, 0)) - toNumber(arg(arguments, 1))));
    operations.put("*", (data, arguments) -> {
      double total = 1;
      for (Object argument : arguments) {
        total *= toNumber(argument);
      }
      return narrow(total);
    });
    operations.put("/", (data, arguments) ->
        narrow(toNumber(arg(arguments, 0)) / toNumber(arg(arguments, 1))));
    operations.put("%", (data, arguments) ->
        narrow(toNumber(arg(arguments, 0)) % toNumber(arg(arguments, 1))));
    operations.put("min", (data, arguments) -> {
      Double best = null;
      for (Object argument : arguments) {
        double value = toNumber(argument);
        best = best == null ? value : Math.min(best, value);
      }
      return best == null ? null : narrow(best);
    });
    operations.put("max", (data, arguments) -> {
      Double best = null;
      for (Object argument : arguments) {
        double value = toNumber(argument);
        best = best == null ? value : Math.max(best, value);
      }
      return best == null ? null : narrow(best);
    });
    operations.put("cat", (data, arguments) -> {
      StringBuilder sb = new StringBuilder();
      for (Object argument : arguments) {
        sb.append(toStringValue(argument));
      }
      return sb.toString();
    });
    operations.put("substr", (data, arguments) -> {
      String text = toStringValue(arg(arguments, 0));
      int start = (int) toNumber(arg(arguments, 1));
      if (start < 0) {
        start = Math.max(0, text.length() + start);
      }
      start = Math.min(start, text.length());
      if (arguments.size() < 3) {
        return text.substring(start);
      }
      int length = (int) toNumber(arg(arguments, 2));
      int end = length < 0 ? Math.max(start, text.length() + length) : Math.min(text.length(), start + length);
      return text.substring(start, end);
    });
    operations.put("in", (data, arguments) -> {
      Object needle = arg(arguments, 0);
      Object haystack = arg(arguments, 1);
      if (haystack instanceof String text) {
        return text.contains(toStringValue(needle));
      }
      if (haystack instanceof List<?> list) {
        for (Object item : list) {
          if (looseEquals(item, needle)) {
            return true;
          }
        }
      }
      return false;
    });
    operations.put("merge", (data, arguments) -> flatten(arguments));
    operations.put("log", (data, arguments) -> arg(arguments, 0));
    return operations;
  }

  private static Object arg(List<Object> arguments, int index) {
    return index < arguments.size() ? arguments.get(index) : null;
  }

  private static List<Object> flatten(List<Object> arguments) {
    List<Object> out = new ArrayList<>();
    for (Object argument : arguments) {
      if (argument instanceof List<?> list) {
        for (Object item : list) {
          out.add(item);
        }
      } else {
        out.add(argument);
      }
    }
    return out;
  }

  public static boolean toBool(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Double d) {
      return !d.isNaN() && d != 0;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (value instanceof String s) {
      return !s.isEmpty();
    }
    if (value instanceof List<?> list) {
      return !list.isEmpty();
    }
    return true;
  }

  public static double toNumber(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value == null) {
      return 0;
    }
    if (value instanceof Boolean b) {
      return b ? 1 : 0;
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        return 0;
      }
      return list.size() > 1 ? Double.NaN : toNumber(list.get(0));
    }
    if (value instanceof Map<?, ?>) {
      return Double.NaN;
    }
    String text = String.valueOf(value).strip();
    String lowered = text.toLowerCase(Locale.ROOT);
    if (lowered.equals("inf") || lowered.equals("-inf") || lowered.equals("+inf")) {
      return Double.NaN;
    }
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  public static String toStringValue(Object value) {
    if (value instanceof String s) {
      return s;
    }
    if (value == null) {
      return "null";
    }
    if (value instanceof Boolean b) {
      return b ? "true" : "false";
    }
    if (value instanceof Double || value instanceof Float) {
      return String.format(Locale.ROOT, "%.15g", ((Number) value).doubleValue())
          .replaceAll("0+$", "")
          .replaceAll("\\.$", "");
    }
    if (value instanceof Number n) {
      return String.valueOf(n.longValue());
    }
    if (value instanceof List<?> list) {
      List<String> parts = new ArrayList<>();
      for (Object item : list) {
        parts.add(toStringValue(item));
      }
      return String.join(",", parts);
    }
    if (value instanceof Map<?, ?>) {
      return "[object Object]";
    }
    return String.valueOf(value);
  }

  private static Object narrow(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
      return (long) value;
    }
    return value;
  }

  public static boolean strictEquals(Object a, Object b) {
    if (a == null || b == null) {
      return a == b;
    }
    if (a.getClass() != b.getClass()) {
      return false;
    }
    return a.equals(b);
  }

  public static boolean looseEquals(Object a, Object b) {
    if (a != null && b != null && a.getClass() == b.getClass()) {
      if (a instanceof List<?> || a instanceof Map<?, ?>) {
        return a == b;
      }
      return a.equals(b);
    }
    if (a instanceof Number) {
      return toNumber(a) == toNumber(b);
    }
    if (b instanceof Number) {
      return toNumber(a) == toNumber(b);
    }
    if (a == null || b == null) {
      return false;
    }
    if (a instanceof String) {
      if (b instanceof Boolean) {
        return toNumber(a) == toNumber(b);
      }
      if (b instanceof List<?> || b instanceof Map<?, ?>) {
        return a.equals(toStringValue(b));
      }
      return false;
    }
    if (a instanceof Boolean) {
      return toNumber(a) == toNumber(b);
    }
    if (a instanceof List<?> || a instanceof Map<?, ?>) {
      if (b instanceof List<?> || b instanceof Map<?, ?>) {
        return false;
      }
      if (b instanceof String s) {
        return toStringValue(a).equals(s);
      }
      if (b instanceof Boolean) {
        return toNumber(a) == toNumber(b);
      }
    }
    return false;
  }

  public static boolean lessThan(Object a, Object b) {
    if (a instanceof Number n) {
      return n.doubleValue() < toNumber(b);
    }
    if (b instanceof Number n) {
      return toNumber(a) < n.doubleValue();
    }
    if (a instanceof String s) {
      return s.compareTo(toStringValue(b)) < 0;
    }
    if (b instanceof String s) {
      return toStringValue(a).compareTo(s) < 0;
    }
    return toNumber(a) < toNumber(b);
  }

  public static boolean greaterThan(Object a, Object b) {
    if (a instanceof Number n) {
      return n.doubleValue() > toNumber(b);
    }
    if (b instanceof Number n) {
      return toNumber(a) > n.doubleValue();
    }
    if (a instanceof String s) {
      return s.compareTo(toStringValue(b)) > 0;
    }
    if (b instanceof String s) {
      return toStringValue(a).compareTo(s) > 0;
    }
    return toNumber(a) > toNumber(b);
  }
}
