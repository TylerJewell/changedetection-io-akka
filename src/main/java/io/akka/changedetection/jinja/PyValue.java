package io.akka.changedetection.jinja;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Values behaving the way a template author expects them to.
 *
 * <p>The templates were written against a language where an empty list is false, an integer
 * divided by an integer is a float, a missing key on a mapping is an error rather than null,
 * and a number printed into markup has no trailing decimal point unless it needs one. Each of
 * those shows up directly on a rendered page, so they are reproduced here rather than left to
 * whatever the host language happens to do.
 */
public final class PyValue {

  private PyValue() {}

  /** The value a name that was never set evaluates to. */
  public static final Object UNDEFINED = new Object() {
    @Override
    public String toString() {
      return "";
    }
  };

  /**
   * A fixed group of values, which prints with parentheses rather than brackets.
   *
   * <p>The difference shows: a template that prints a mapping's items prints them as pairs,
   * and the interface has places where that printed form is what appears on the page.
   */
  public static final class Tuple extends java.util.ArrayList<Object> {
    public Tuple(java.util.Collection<?> items) {
      super(items);
    }

    public Tuple(Object... items) {
      super(java.util.List.of(items));
    }
  }

  /** A string that must not be escaped again when it reaches the output. */
  public static final class Markup implements CharSequence {
    private final String value;

    public Markup(String value) {
      this.value = value == null ? "" : value;
    }

    public String value() {
      return value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new Markup(value.substring(start, end));
    }

    @Override
    public String toString() {
      return value;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Markup markup && markup.value.equals(value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  public static boolean truthy(Object value) {
    if (value == null || value == UNDEFINED) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (value instanceof CharSequence s) {
      return s.length() > 0;
    }
    if (value instanceof Collection<?> c) {
      return !c.isEmpty();
    }
    if (value instanceof Map<?, ?> m) {
      return !m.isEmpty();
    }
    if (value instanceof Object[] a) {
      return a.length > 0;
    }
    return true;
  }

  /** A value written into the output, with numbers spelled the way the language spells them. */
  public static String asString(Object value) {
    if (value == null) {
      return "None";
    }
    if (value == UNDEFINED) {
      return "";
    }
    if (value instanceof Boolean b) {
      return b ? "True" : "False";
    }
    if (value instanceof Double || value instanceof Float) {
      return io.akka.changedetection.text.PythonJson.floatRepr(((Number) value).doubleValue());
    }
    if (value instanceof CharSequence s) {
      return s.toString();
    }
    if (value instanceof Map<?, ?> map) {
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(repr(entry.getKey())).append(": ").append(repr(entry.getValue()));
      }
      return sb.append('}').toString();
    }
    if (value instanceof Tuple tuple) {
      StringBuilder sb = new StringBuilder("(");
      boolean first = true;
      for (Object item : tuple) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(repr(item));
      }
      if (tuple.size() == 1) {
        sb.append(',');
      }
      return sb.append(')').toString();
    }
    if (value instanceof Collection<?> collection) {
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (Object item : collection) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(repr(item));
      }
      return sb.append(']').toString();
    }
    return String.valueOf(value);
  }

  public static String repr(Object value) {
    if (value instanceof CharSequence s) {
      return "'" + s.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
    return asString(value);
  }

  /** Attribute lookup, trying the mapping key first as the language does. */
  public static Object getAttribute(Object target, String name) {
    if (target == null || target == UNDEFINED) {
      return UNDEFINED;
    }
    if (target instanceof Map<?, ?> map) {
      if (map.containsKey(name)) {
        return map.get(name);
      }
    }
    if (target instanceof Attributed attributed) {
      Object value = attributed.attribute(name);
      if (value != UNDEFINED) {
        return value;
      }
    }
    Object method = boundMethod(target, name);
    if (method != null) {
      return method;
    }
    return UNDEFINED;
  }

  /** Subscript lookup, trying the index first as the language does. */
  public static Object getItem(Object target, Object index) {
    if (target == null || target == UNDEFINED) {
      return UNDEFINED;
    }
    if (target instanceof Map<?, ?> map) {
      if (map.containsKey(index)) {
        return map.get(index);
      }
      if (index instanceof CharSequence s && map.containsKey(s.toString())) {
        return map.get(s.toString());
      }
      return UNDEFINED;
    }
    if (index instanceof Number n) {
      int i = n.intValue();
      if (target instanceof List<?> list) {
        int size = list.size();
        int at = i < 0 ? size + i : i;
        return at >= 0 && at < size ? list.get(at) : UNDEFINED;
      }
      if (target instanceof CharSequence s) {
        int size = s.length();
        int at = i < 0 ? size + i : i;
        return at >= 0 && at < size ? String.valueOf(s.charAt(at)) : UNDEFINED;
      }
      if (target instanceof Object[] array) {
        int at = i < 0 ? array.length + i : i;
        return at >= 0 && at < array.length ? array[at] : UNDEFINED;
      }
      if (target instanceof Iterable<?> iterable) {
        List<Object> items = new ArrayList<>();
        for (Object item : iterable) {
          items.add(item);
        }
        int at = i < 0 ? items.size() + i : i;
        return at >= 0 && at < items.size() ? items.get(at) : UNDEFINED;
      }
    }
    if (index instanceof CharSequence name) {
      return getAttribute(target, name.toString());
    }
    return UNDEFINED;
  }

  /** Anything a template may read attributes from that is not a mapping. */
  public interface Attributed {
    Object attribute(String name);
  }

  /** Anything a template may call. */
  public interface Callable {
    Object call(List<Object> positional, Map<String, Object> keyword);
  }

  private static Object boundMethod(Object target, String name) {
    if (target instanceof Map<?, ?> map) {
      switch (name) {
        case "get":
          return (Callable) (positional, keyword) -> {
            Object key = positional.isEmpty() ? null : positional.get(0);
            Object fallback = positional.size() > 1 ? positional.get(1) : null;
            return map.containsKey(key) ? map.get(key) : fallback;
          };
        case "keys":
          return (Callable) (positional, keyword) -> new ArrayList<>(map.keySet());
        case "values":
          return (Callable) (positional, keyword) -> new ArrayList<>(map.values());
        case "items":
          return (Callable) (positional, keyword) -> itemsOf(map);
        default:
          break;
      }
    }
    if (target instanceof CharSequence text) {
      String s = text.toString();
      switch (name) {
        case "strip":
          return (Callable) (positional, keyword) -> s.strip();
        case "lstrip":
          return (Callable) (positional, keyword) -> s.stripLeading();
        case "rstrip":
          return (Callable) (positional, keyword) -> s.stripTrailing();
        case "lower":
          return (Callable) (positional, keyword) -> s.toLowerCase(java.util.Locale.ROOT);
        case "upper":
          return (Callable) (positional, keyword) -> s.toUpperCase(java.util.Locale.ROOT);
        case "title":
          return (Callable) (positional, keyword) -> Filters.title(s);
        case "capitalize":
          return (Callable) (positional, keyword) -> Filters.capitalize(s);
        case "startswith":
          return (Callable) (positional, keyword) -> s.startsWith(asString(positional.get(0)));
        case "endswith":
          return (Callable) (positional, keyword) -> s.endsWith(asString(positional.get(0)));
        case "split":
          return (Callable) (positional, keyword) -> {
            if (positional.isEmpty()) {
              return io.akka.changedetection.text.PythonText.splitOnWhitespace(s);
            }
            String separator = asString(positional.get(0));
            List<String> parts = new ArrayList<>(List.of(s.split(java.util.regex.Pattern.quote(separator), -1)));
            return parts;
          };
        case "replace":
          return (Callable) (positional, keyword) ->
              s.replace(asString(positional.get(0)), asString(positional.get(1)));
        case "format":
          return (Callable) (positional, keyword) -> Filters.pyFormat(s, positional, keyword);
        case "join":
          return (Callable) (positional, keyword) -> {
            List<String> parts = new ArrayList<>();
            for (Object item : iterate(positional.get(0))) {
              parts.add(asString(item));
            }
            return String.join(s, parts);
          };
        case "count":
          return (Callable) (positional, keyword) -> {
            String needle = asString(positional.get(0));
            if (needle.isEmpty()) {
              return (long) (s.length() + 1);
            }
            long count = 0;
            int at = 0;
            while ((at = s.indexOf(needle, at)) >= 0) {
              count++;
              at += needle.length();
            }
            return count;
          };
        default:
          break;
      }
    }
    if (target instanceof List<?> list) {
      switch (name) {
        case "append":
          return (Callable) (positional, keyword) -> {
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) list;
            mutable.add(positional.isEmpty() ? null : positional.get(0));
            return UNDEFINED;
          };
        case "index":
          return (Callable) (positional, keyword) -> (long) list.indexOf(positional.get(0));
        case "count":
          return (Callable) (positional, keyword) -> {
            long count = 0;
            for (Object item : list) {
              if (equal(item, positional.get(0))) {
                count++;
              }
            }
            return count;
          };
        default:
          break;
      }
    }
    return null;
  }

  public static List<Object> itemsOf(Map<?, ?> map) {
    List<Object> items = new ArrayList<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      items.add(new Tuple(entry.getKey(), entry.getValue()));
    }
    return items;
  }

  public static List<Object> iterate(Object value) {
    List<Object> out = new ArrayList<>();
    if (value == null || value == UNDEFINED) {
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      out.addAll(map.keySet());
      return out;
    }
    if (value instanceof Collection<?> collection) {
      out.addAll(collection);
      return out;
    }
    if (value instanceof Object[] array) {
      out.addAll(List.of(array));
      return out;
    }
    if (value instanceof CharSequence text) {
      for (int i = 0; i < text.length(); i++) {
        out.add(String.valueOf(text.charAt(i)));
      }
      return out;
    }
    if (value instanceof Iterable<?> iterable) {
      for (Object item : iterable) {
        out.add(item);
      }
      return out;
    }
    out.add(value);
    return out;
  }

  public static boolean equal(Object a, Object b) {
    if (a == UNDEFINED) {
      a = null;
    }
    if (b == UNDEFINED) {
      b = null;
    }
    if (a instanceof Number x && b instanceof Number y) {
      return x.doubleValue() == y.doubleValue();
    }
    if (a instanceof CharSequence x && b instanceof CharSequence y) {
      return x.toString().equals(y.toString());
    }
    return Objects.equals(a, b);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static int compare(Object a, Object b) {
    if (a instanceof Number x && b instanceof Number y) {
      return Double.compare(x.doubleValue(), y.doubleValue());
    }
    if (a instanceof CharSequence x && b instanceof CharSequence y) {
      return x.toString().compareTo(y.toString());
    }
    if (a instanceof Comparable x && b != null && a.getClass().isInstance(b)) {
      return x.compareTo(b);
    }
    return asString(a).compareTo(asString(b));
  }

  public static boolean contains(Object haystack, Object needle) {
    if (haystack instanceof Map<?, ?> map) {
      return map.containsKey(needle) || (needle instanceof CharSequence s && map.containsKey(s.toString()));
    }
    if (haystack instanceof CharSequence text) {
      return text.toString().contains(asString(needle));
    }
    for (Object item : iterate(haystack)) {
      if (equal(item, needle)) {
        return true;
      }
    }
    return false;
  }

  public static Object add(Object a, Object b) {
    if (a instanceof CharSequence || b instanceof CharSequence) {
      if (a instanceof CharSequence && b instanceof CharSequence) {
        return a.toString() + b.toString();
      }
    }
    if (a instanceof List<?> x && b instanceof List<?> y) {
      List<Object> out = new ArrayList<>(x);
      out.addAll(y);
      return out;
    }
    if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
      Map<Object, Object> out = new LinkedHashMap<>(x);
      out.putAll(y);
      return out;
    }
    return arithmetic(a, b, "+");
  }

  public static Object arithmetic(Object a, Object b, String operator) {
    if (operator.equals("*") && a instanceof CharSequence s && isIntegral(b)) {
      return s.toString().repeat(Math.max(0, (int) toDouble(b)));
    }
    if (operator.equals("*") && b instanceof CharSequence s && isIntegral(a)) {
      return s.toString().repeat(Math.max(0, (int) toDouble(a)));
    }
    if (operator.equals("%") && a instanceof CharSequence s) {
      return Filters.percentFormat(s.toString(), b);
    }
    double left = toDouble(a);
    double right = toDouble(b);
    boolean bothIntegral = isIntegral(a) && isIntegral(b);
    switch (operator) {
      case "+":
        return bothIntegral ? (Object) (long) (left + right) : (Object) (left + right);
      case "-":
        return bothIntegral ? (Object) (long) (left - right) : (Object) (left - right);
      case "*":
        if (a instanceof CharSequence s && isIntegral(b)) {
          return s.toString().repeat(Math.max(0, (int) right));
        }
        return bothIntegral ? (Object) (long) (left * right) : (Object) (left * right);
      case "/":
        // Division always produces a real number, as it does in the language the templates
        // were written for -- an integer result here would print without its decimal part.
        return left / right;
      case "//":
        return bothIntegral ? (Object) (long) Math.floor(left / right) : (Object) Math.floor(left / right);
      case "%":
        if (a instanceof CharSequence s) {
          return Filters.percentFormat(s.toString(), b);
        }
        double result = left % right;
        if (result != 0 && (result < 0) != (right < 0)) {
          result += right;
        }
        return bothIntegral ? (Object) (long) result : (Object) result;
      case "**":
        return bothIntegral && right >= 0
            ? (Object) (long) Math.pow(left, right)
            : (Object) Math.pow(left, right);
      default:
        throw new JinjaException("unknown operator " + operator);
    }
  }

  public static boolean isIntegral(Object value) {
    return value instanceof Long || value instanceof Integer || value instanceof Short
        || value instanceof Byte || value instanceof java.math.BigInteger
        || value instanceof Boolean;
  }

  public static double toDouble(Object value) {
    if (value == null || value == UNDEFINED) {
      return 0;
    }
    if (value instanceof Boolean b) {
      return b ? 1 : 0;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return Double.parseDouble(asString(value).strip());
    } catch (NumberFormatException e) {
      throw new JinjaException("cannot use " + repr(value) + " as a number");
    }
  }

  public static int length(Object value) {
    if (value == null || value == UNDEFINED) {
      return 0;
    }
    if (value instanceof CharSequence s) {
      return s.length();
    }
    if (value instanceof Collection<?> c) {
      return c.size();
    }
    if (value instanceof Map<?, ?> m) {
      return m.size();
    }
    if (value instanceof Object[] a) {
      return a.length;
    }
    return 0;
  }
}
