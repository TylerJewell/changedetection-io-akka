package io.akka.changedetection.jinja;

import io.akka.changedetection.text.PythonJson;
import io.akka.changedetection.text.PythonText;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The filters a template may apply to a value. */
public final class Filters {

  private Filters() {}

  /** One filter: the value, then whatever the template passed alongside it. */
  public interface Filter {
    Object apply(Environment environment, Object value, List<Object> positional,
        Map<String, Object> keyword);
  }

  public static Map<String, Filter> standard() {
    Map<String, Filter> filters = new LinkedHashMap<>();

    filters.put("safe", (env, value, p, k) -> new PyValue.Markup(PyValue.asString(value)));
    filters.put("escape", (env, value, p, k) -> escapeMarkup(value));
    filters.put("e", (env, value, p, k) -> escapeMarkup(value));
    filters.put("forceescape",
        (env, value, p, k) -> new PyValue.Markup(escapeHtml(PyValue.asString(value))));
    filters.put("striptags",
        (env, value, p, k) -> stripTags(PyValue.asString(value)));

    filters.put("length", (env, value, p, k) -> (long) PyValue.length(value));
    filters.put("count", (env, value, p, k) -> (long) PyValue.length(value));

    filters.put("upper", (env, value, p, k) -> PyValue.asString(value).toUpperCase(Locale.ROOT));
    filters.put("lower", (env, value, p, k) -> PyValue.asString(value).toLowerCase(Locale.ROOT));
    filters.put("title", (env, value, p, k) -> title(PyValue.asString(value)));
    filters.put("capitalize", (env, value, p, k) -> capitalize(PyValue.asString(value)));
    filters.put("trim", (env, value, p, k) ->
        p.isEmpty() ? PythonText.strip(PyValue.asString(value))
            : trimChars(PyValue.asString(value), PyValue.asString(p.get(0))));
    filters.put("string", (env, value, p, k) -> PyValue.asString(value));
    filters.put("int", (env, value, p, k) -> toLong(value, p.isEmpty() ? 0L : toLong(p.get(0), 0L)));
    filters.put("float", (env, value, p, k) -> toDouble(value, p.isEmpty() ? 0.0 : PyValue.toDouble(p.get(0))));
    filters.put("abs", (env, value, p, k) ->
        PyValue.isIntegral(value) ? (Object) Math.abs(toLong(value, 0L))
            : (Object) Math.abs(PyValue.toDouble(value)));
    filters.put("round", (env, value, p, k) -> {
      int precision = p.isEmpty() ? 0 : (int) toLong(p.get(0), 0L);
      double scale = Math.pow(10, precision);
      String method = k.containsKey("method") ? PyValue.asString(k.get("method")) : "common";
      double raw = PyValue.toDouble(value) * scale;
      double rounded = switch (method) {
        case "ceil" -> Math.ceil(raw);
        case "floor" -> Math.floor(raw);
        default -> Math.round(raw);
      };
      return rounded / scale;
    });

    filters.put("default", Filters::defaultFilter);
    filters.put("d", Filters::defaultFilter);

    filters.put("join", (env, value, p, k) -> {
      String separator = p.isEmpty() ? "" : PyValue.asString(p.get(0));
      String attribute = p.size() > 1 ? PyValue.asString(p.get(1))
          : (k.containsKey("attribute") ? PyValue.asString(k.get("attribute")) : null);
      List<String> parts = new ArrayList<>();
      for (Object item : PyValue.iterate(value)) {
        Object element = attribute == null ? item : PyValue.getAttribute(item, attribute);
        parts.add(PyValue.asString(element));
      }
      return String.join(separator, parts);
    });

    filters.put("list", (env, value, p, k) -> PyValue.iterate(value));
    filters.put("first", (env, value, p, k) -> {
      List<Object> items = PyValue.iterate(value);
      return items.isEmpty() ? PyValue.UNDEFINED : items.get(0);
    });
    filters.put("last", (env, value, p, k) -> {
      List<Object> items = PyValue.iterate(value);
      return items.isEmpty() ? PyValue.UNDEFINED : items.get(items.size() - 1);
    });
    filters.put("reverse", (env, value, p, k) -> {
      if (value instanceof CharSequence s) {
        return new StringBuilder(s.toString()).reverse().toString();
      }
      List<Object> items = new ArrayList<>(PyValue.iterate(value));
      java.util.Collections.reverse(items);
      return items;
    });
    filters.put("sort", (env, value, p, k) -> {
      List<Object> items = new ArrayList<>(PyValue.iterate(value));
      boolean reverse = k.containsKey("reverse") && PyValue.truthy(k.get("reverse"));
      boolean caseSensitive = k.containsKey("case_sensitive") && PyValue.truthy(k.get("case_sensitive"));
      String attribute = k.containsKey("attribute") ? PyValue.asString(k.get("attribute")) : null;
      Comparator<Object> comparator = (a, b) -> {
        Object left = attribute == null ? a : resolveAttributePath(a, attribute);
        Object right = attribute == null ? b : resolveAttributePath(b, attribute);
        if (!caseSensitive && left instanceof CharSequence x && right instanceof CharSequence y) {
          return x.toString().toLowerCase(Locale.ROOT).compareTo(y.toString().toLowerCase(Locale.ROOT));
        }
        return PyValue.compare(left, right);
      };
      items.sort(reverse ? comparator.reversed() : comparator);
      return items;
    });
    filters.put("unique", (env, value, p, k) -> new ArrayList<>(new LinkedHashSet<>(PyValue.iterate(value))));
    filters.put("sum", (env, value, p, k) -> {
      String attribute = p.isEmpty()
          ? (k.containsKey("attribute") ? PyValue.asString(k.get("attribute")) : null)
          : PyValue.asString(p.get(0));
      double total = 0;
      boolean integral = true;
      for (Object item : PyValue.iterate(value)) {
        Object element = attribute == null ? item : resolveAttributePath(item, attribute);
        integral = integral && PyValue.isIntegral(element);
        total += PyValue.toDouble(element);
      }
      return integral ? (Object) (long) total : (Object) total;
    });
    filters.put("min", (env, value, p, k) -> extreme(value, k, true));
    filters.put("max", (env, value, p, k) -> extreme(value, k, false));

    filters.put("replace", (env, value, p, k) -> {
      String text = PyValue.asString(value);
      String from = PyValue.asString(p.get(0));
      String to = p.size() > 1 ? PyValue.asString(p.get(1)) : "";
      if (p.size() > 2) {
        int count = (int) toLong(p.get(2), 0L);
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (int i = 0; i < count; i++) {
          int found = text.indexOf(from, at);
          if (found < 0) {
            break;
          }
          sb.append(text, at, found).append(to);
          at = found + from.length();
        }
        sb.append(text.substring(at));
        return sb.toString();
      }
      return text.replace(from, to);
    });

    // The filter always hands its arguments over as a positional group, so a format string
    // written with named placeholders has nothing to look them up in and fails -- which is
    // what the original does, and a template relying on it would otherwise silently differ.
    filters.put("format", (env, value, p, k) -> percentFormat(PyValue.asString(value), p));
    filters.put("indent", (env, value, p, k) -> {
      int width = p.isEmpty() ? 4 : (int) toLong(p.get(0), 4L);
      boolean first = p.size() > 1 ? PyValue.truthy(p.get(1))
          : (k.containsKey("first") && PyValue.truthy(k.get("first")));
      String padding = " ".repeat(width);
      List<String> lines = PythonText.splitLines(PyValue.asString(value));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < lines.size(); i++) {
        if (i > 0 || first) {
          sb.append(padding);
        }
        sb.append(lines.get(i));
        if (i < lines.size() - 1) {
          sb.append('\n');
        }
      }
      return sb.toString();
    });
    filters.put("truncate", (env, value, p, k) -> {
      int length = p.isEmpty() ? 255 : (int) toLong(p.get(0), 255L);
      String text = PyValue.asString(value);
      String end = k.containsKey("end") ? PyValue.asString(k.get("end")) : "...";
      if (text.length() <= length) {
        return text;
      }
      return text.substring(0, Math.max(0, length - end.length())) + end;
    });
    filters.put("center", (env, value, p, k) -> {
      int width = p.isEmpty() ? 80 : (int) toLong(p.get(0), 80L);
      return io.akka.changedetection.text.inscriptis.HtmlProperties.HorizontalAlignment.CENTER
          .format(PyValue.asString(value), width);
    });
    filters.put("wordcount",
        (env, value, p, k) -> (long) PythonText.splitOnWhitespace(PyValue.asString(value)).size());

    filters.put("tojson", (env, value, p, k) -> new PyValue.Markup(toJson(value)));
    filters.put("urlencode", (env, value, p, k) -> urlEncode(value));

    filters.put("batch", (env, value, p, k) -> {
      int size = (int) toLong(p.get(0), 1L);
      Object filler = p.size() > 1 ? p.get(1) : (k.containsKey("fill_with") ? k.get("fill_with") : null);
      List<Object> items = PyValue.iterate(value);
      List<Object> out = new ArrayList<>();
      List<Object> current = new ArrayList<>();
      for (Object item : items) {
        current.add(item);
        if (current.size() == size) {
          out.add(current);
          current = new ArrayList<>();
        }
      }
      if (!current.isEmpty()) {
        if (filler != null) {
          while (current.size() < size) {
            current.add(filler);
          }
        }
        out.add(current);
      }
      return out;
    });
    filters.put("slice", (env, value, p, k) -> {
      int count = (int) toLong(p.get(0), 1L);
      Object filler = p.size() > 1 ? p.get(1) : null;
      List<Object> items = PyValue.iterate(value);
      List<Object> out = new ArrayList<>();
      int itemsPerSlice = items.size() / Math.max(1, count);
      int slicesWithExtra = items.size() % Math.max(1, count);
      int offset = 0;
      for (int i = 0; i < count; i++) {
        int start = offset + i * itemsPerSlice;
        if (i < slicesWithExtra) {
          offset++;
        }
        int end = offset + (i + 1) * itemsPerSlice;
        List<Object> slice = new ArrayList<>(items.subList(Math.min(start, items.size()),
            Math.min(end, items.size())));
        if (filler != null && i >= slicesWithExtra) {
          slice.add(filler);
        }
        out.add(slice);
      }
      return out;
    });

    filters.put("map", (env, value, p, k) -> {
      String attribute = k.containsKey("attribute") ? PyValue.asString(k.get("attribute")) : null;
      List<Object> out = new ArrayList<>();
      if (attribute != null) {
        for (Object item : PyValue.iterate(value)) {
          out.add(resolveAttributePath(item, attribute));
        }
        return out;
      }
      String filterName = PyValue.asString(p.get(0));
      List<Object> rest = p.subList(1, p.size());
      for (Object item : PyValue.iterate(value)) {
        out.add(env.applyFilter(filterName, item, rest, Map.of()));
      }
      return out;
    });
    filters.put("select", (env, value, p, k) -> selectOrReject(env, value, p, k, true, null));
    filters.put("reject", (env, value, p, k) -> selectOrReject(env, value, p, k, false, null));
    filters.put("selectattr", (env, value, p, k) -> {
      String attribute = PyValue.asString(p.get(0));
      return selectOrReject(env, value, p.subList(1, p.size()), k, true, attribute);
    });
    filters.put("rejectattr", (env, value, p, k) -> {
      String attribute = PyValue.asString(p.get(0));
      return selectOrReject(env, value, p.subList(1, p.size()), k, false, attribute);
    });
    filters.put("attr", (env, value, p, k) -> PyValue.getAttribute(value, PyValue.asString(p.get(0))));

    filters.put("dictsort", (env, value, p, k) -> {
      List<Object> items =
          value instanceof Map<?, ?> map ? PyValue.itemsOf(map) : new ArrayList<>();
      boolean caseSensitive = !p.isEmpty() && PyValue.truthy(p.get(0));
      String by = p.size() > 1 ? PyValue.asString(p.get(1))
          : (k.containsKey("by") ? PyValue.asString(k.get("by")) : "key");
      boolean reverse = k.containsKey("reverse") && PyValue.truthy(k.get("reverse"));
      int index = by.equals("value") ? 1 : 0;
      Comparator<Object> comparator = (a, b) -> {
        Object left = PyValue.getItem(a, (long) index);
        Object right = PyValue.getItem(b, (long) index);
        if (!caseSensitive && left instanceof CharSequence x && right instanceof CharSequence y) {
          return x.toString().toLowerCase(Locale.ROOT)
              .compareTo(y.toString().toLowerCase(Locale.ROOT));
        }
        return PyValue.compare(left, right);
      };
      items.sort(reverse ? comparator.reversed() : comparator);
      return items;
    });

    filters.put("groupby", (env, value, p, k) -> {
      String attribute = PyValue.asString(p.get(0));
      Map<Object, List<Object>> groups = new LinkedHashMap<>();
      for (Object item : PyValue.iterate(value)) {
        groups.computeIfAbsent(resolveAttributePath(item, attribute), key -> new ArrayList<>())
            .add(item);
      }
      List<Object> out = new ArrayList<>();
      List<Object> keys = new ArrayList<>(groups.keySet());
      keys.sort(PyValue::compare);
      for (Object key : keys) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("grouper", key);
        group.put("list", groups.get(key));
        out.add(group);
      }
      return out;
    });

    filters.put("items", (env, value, p, k) ->
        value instanceof Map<?, ?> map ? PyValue.itemsOf(map) : new ArrayList<>());
    filters.put("enumerate", (env, value, p, k) -> {
      List<Object> out = new ArrayList<>();
      long index = p.isEmpty() ? 0 : toLong(p.get(0), 0L);
      for (Object item : PyValue.iterate(value)) {
        out.add(new PyValue.Tuple(index++, item));
      }
      return out;
    });
    filters.put("pprint", (env, value, p, k) -> PyValue.asString(value));

    filters.put("regex_replace", (env, value, p, k) -> regexReplace(
        PyValue.asString(value),
        PyValue.asString(p.get(0)),
        p.size() > 1 ? PyValue.asString(p.get(1)) : "",
        p.size() > 2 ? (int) toLong(p.get(2), 0L) : 0));

    return filters;
  }

  private static Object defaultFilter(Environment environment, Object value, List<Object> positional,
      Map<String, Object> keyword) {
    Object fallback = positional.isEmpty() ? "" : positional.get(0);
    boolean whenFalsy = positional.size() > 1
        ? PyValue.truthy(positional.get(1))
        : (keyword.containsKey("boolean") && PyValue.truthy(keyword.get("boolean")));
    if (value == PyValue.UNDEFINED) {
      return fallback;
    }
    if (whenFalsy && !PyValue.truthy(value)) {
      return fallback;
    }
    return value;
  }

  private static Object extreme(Object value, Map<String, Object> keyword, boolean smallest) {
    String attribute = keyword.containsKey("attribute")
        ? PyValue.asString(keyword.get("attribute")) : null;
    Object best = null;
    Object bestKey = null;
    for (Object item : PyValue.iterate(value)) {
      Object key = attribute == null ? item : resolveAttributePath(item, attribute);
      if (best == null
          || (smallest ? PyValue.compare(key, bestKey) < 0 : PyValue.compare(key, bestKey) > 0)) {
        best = item;
        bestKey = key;
      }
    }
    return best == null ? PyValue.UNDEFINED : best;
  }

  private static Object selectOrReject(Environment environment, Object value,
      List<Object> positional, Map<String, Object> keyword, boolean keep, String attribute) {
    List<Object> out = new ArrayList<>();
    String testName = positional.isEmpty() ? null : PyValue.asString(positional.get(0));
    List<Object> arguments =
        positional.isEmpty() ? List.of() : new ArrayList<>(positional.subList(1, positional.size()));
    for (Object item : PyValue.iterate(value)) {
      Object subject = attribute == null ? item : resolveAttributePath(item, attribute);
      boolean matched = testName == null
          ? PyValue.truthy(subject)
          : environment.applyTest(testName, subject, arguments);
      if (matched == keep) {
        out.add(item);
      }
    }
    return out;
  }

  /** A dotted path, so a filter's attribute argument may name something nested. */
  public static Object resolveAttributePath(Object target, String path) {
    Object current = target;
    for (String part : path.split("\\.")) {
      if (part.matches("\\d+")) {
        current = PyValue.getItem(current, Long.valueOf(part));
      } else {
        current = PyValue.getAttribute(current, part);
      }
    }
    return current;
  }

  public static PyValue.Markup escapeMarkup(Object value) {
    if (value instanceof PyValue.Markup markup) {
      return markup;
    }
    return new PyValue.Markup(escapeHtml(PyValue.asString(value)));
  }

  /** The escape the templates rely on: the five characters that can change markup. */
  public static String escapeHtml(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&#34;");
        case '\'' -> sb.append("&#39;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  public static String stripTags(String text) {
    String stripped = TAG.matcher(text).replaceAll("");
    stripped = org.jsoup.parser.Parser.unescapeEntities(stripped, false);
    return String.join(" ", PythonText.splitOnWhitespace(stripped));
  }

  public static String title(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    boolean startOfWord = true;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isLetter(c)) {
        sb.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
        startOfWord = false;
      } else {
        sb.append(c);
        startOfWord = !Character.isDigit(c);
      }
    }
    return sb.toString();
  }

  public static String capitalize(String text) {
    if (text.isEmpty()) {
      return text;
    }
    return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase(Locale.ROOT);
  }

  private static String trimChars(String text, String characters) {
    int start = 0;
    int end = text.length();
    while (start < end && characters.indexOf(text.charAt(start)) >= 0) {
      start++;
    }
    while (end > start && characters.indexOf(text.charAt(end - 1)) >= 0) {
      end--;
    }
    return text.substring(start, end);
  }

  public static long toLong(Object value, long fallback) {
    if (value == null || value == PyValue.UNDEFINED) {
      return fallback;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value instanceof Boolean b) {
      return b ? 1 : 0;
    }
    try {
      String text = PyValue.asString(value).strip();
      return (long) Double.parseDouble(text);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double toDouble(Object value, double fallback) {
    try {
      return PyValue.toDouble(value);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  public static String toJson(Object value) {
    try {
      return PythonJson.dumpsCompact(
          PythonJson.MAPPER.valueToTree(unwrap(value)))
          .replace("<", "\\u003c")
          .replace(">", "\\u003e")
          .replace("&", "\\u0026")
          .replace("'", "\\u0027");
    } catch (Exception e) {
      throw new JinjaException("cannot write " + PyValue.repr(value) + " as JSON", e);
    }
  }

  private static Object unwrap(Object value) {
    if (value == PyValue.UNDEFINED) {
      return null;
    }
    if (value instanceof PyValue.Markup markup) {
      return markup.value();
    }
    if (value instanceof CharSequence s) {
      return s.toString();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(PyValue.asString(entry.getKey()), unwrap(entry.getValue()));
      }
      return out;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> out = new ArrayList<>();
      for (Object item : collection) {
        out.add(unwrap(item));
      }
      return out;
    }
    if (value instanceof Object[] array) {
      List<Object> out = new ArrayList<>();
      for (Object item : array) {
        out.add(unwrap(item));
      }
      return out;
    }
    return value;
  }

  public static String urlEncode(Object value) {
    if (value instanceof Map<?, ?> map) {
      List<String> parts = new ArrayList<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        parts.add(encodeComponent(PyValue.asString(entry.getKey())) + "="
            + encodeComponent(PyValue.asString(entry.getValue())));
      }
      return String.join("&", parts);
    }
    return encodePath(PyValue.asString(value));
  }

  private static String encodeComponent(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String encodePath(String text) {
    StringBuilder sb = new StringBuilder();
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
          || "/-_.~".indexOf(c) >= 0) {
        sb.append((char) c);
      } else {
        sb.append('%').append(String.format("%02X", c));
      }
    }
    return sb.toString();
  }

  /** The percent-style formatting the templates use for translated strings. */
  public static String percentFormat(String format, Object argument) {
    List<Object> arguments = new ArrayList<>();
    Map<String, Object> named = new LinkedHashMap<>();
    if (argument instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        named.put(PyValue.asString(entry.getKey()), entry.getValue());
      }
    } else if (argument instanceof Collection<?> collection) {
      arguments.addAll(collection);
    } else if (argument instanceof Object[] array) {
      arguments.addAll(List.of(array));
    } else {
      arguments.add(argument);
    }

    StringBuilder sb = new StringBuilder();
    int index = 0;
    for (int i = 0; i < format.length(); i++) {
      char c = format.charAt(i);
      if (c != '%') {
        sb.append(c);
        continue;
      }
      if (i + 1 < format.length() && format.charAt(i + 1) == '%') {
        sb.append('%');
        i++;
        continue;
      }
      int j = i + 1;
      String key = null;
      if (j < format.length() && format.charAt(j) == '(') {
        int close = format.indexOf(')', j);
        if (close > 0) {
          key = format.substring(j + 1, close);
          j = close + 1;
        }
      }
      StringBuilder spec = new StringBuilder();
      while (j < format.length() && "diouxXeEfFgGcrsab".indexOf(format.charAt(j)) < 0) {
        spec.append(format.charAt(j));
        j++;
      }
      if (j >= format.length()) {
        sb.append(format.substring(i));
        break;
      }
      char conversion = format.charAt(j);
      if (key != null && named.isEmpty()) {
        throw new JinjaException("format requires a mapping");
      }
      Object value = key != null
          ? named.get(key)
          : (index < arguments.size() ? arguments.get(index++) : PyValue.UNDEFINED);
      sb.append(formatOne(spec.toString(), conversion, value));
      i = j;
    }
    return sb.toString();
  }

  private static String formatOne(String spec, char conversion, Object value) {
    switch (conversion) {
      case 'd':
      case 'i':
      case 'u':
        return String.format("%" + spec + "d", toLong(value, 0L));
      case 'f':
      case 'F':
      case 'e':
      case 'E':
      case 'g':
      case 'G':
        return String.format("%" + spec + conversion, PyValue.toDouble(value));
      case 'x':
      case 'X':
      case 'o':
        return String.format("%" + spec + conversion, toLong(value, 0L));
      case 'r':
        return PyValue.repr(value);
      default:
        return spec.isEmpty() ? PyValue.asString(value)
            : String.format("%" + spec + "s", PyValue.asString(value));
    }
  }

  /** The brace-style formatting a template may call directly on a string. */
  public static String pyFormat(String format, List<Object> positional, Map<String, Object> keyword) {
    StringBuilder sb = new StringBuilder();
    int automatic = 0;
    for (int i = 0; i < format.length(); i++) {
      char c = format.charAt(i);
      if (c == '{' && i + 1 < format.length() && format.charAt(i + 1) == '{') {
        sb.append('{');
        i++;
        continue;
      }
      if (c == '}' && i + 1 < format.length() && format.charAt(i + 1) == '}') {
        sb.append('}');
        i++;
        continue;
      }
      if (c != '{') {
        sb.append(c);
        continue;
      }
      int close = format.indexOf('}', i);
      if (close < 0) {
        sb.append(format.substring(i));
        break;
      }
      String field = format.substring(i + 1, close);
      i = close;
      String name = field;
      String spec = "";
      int colon = field.indexOf(':');
      if (colon >= 0) {
        name = field.substring(0, colon);
        spec = field.substring(colon + 1);
      }
      Object value;
      if (name.isEmpty()) {
        value = automatic < positional.size() ? positional.get(automatic++) : PyValue.UNDEFINED;
      } else if (name.matches("\\d+")) {
        int at = Integer.parseInt(name);
        value = at < positional.size() ? positional.get(at) : PyValue.UNDEFINED;
      } else {
        value = keyword.containsKey(name) ? keyword.get(name)
            : resolveAttributePath(keyword, name);
      }
      sb.append(applySpec(value, spec));
    }
    return sb.toString();
  }

  private static String applySpec(Object value, String spec) {
    if (spec.isEmpty()) {
      return PyValue.asString(value);
    }
    Matcher m = Pattern.compile("^(?:(.)?([<>^]))?(\\+)?(0)?(\\d+)?(?:\\.(\\d+))?([a-zA-Z%])?$")
        .matcher(spec);
    if (!m.matches()) {
      return PyValue.asString(value);
    }
    String fill = m.group(1) == null ? " " : m.group(1);
    String align = m.group(2);
    String zero = m.group(4);
    Integer width = m.group(5) == null ? null : Integer.valueOf(m.group(5));
    Integer precision = m.group(6) == null ? null : Integer.valueOf(m.group(6));
    String type = m.group(7);

    String text;
    if (type != null && "fFeEgG".contains(type)) {
      text = String.format("%." + (precision == null ? 6 : precision) + type,
          PyValue.toDouble(value));
    } else if (type != null && "dxXob".contains(type)) {
      text = String.format("%" + (type.equals("b") ? "s" : type), type.equals("b")
          ? Long.toBinaryString(toLong(value, 0L)) : toLong(value, 0L));
    } else if (type != null && type.equals("%")) {
      text = String.format("%." + (precision == null ? 6 : precision) + "f",
          PyValue.toDouble(value) * 100) + "%";
    } else {
      text = PyValue.asString(value);
      if (precision != null && text.length() > precision) {
        text = text.substring(0, precision);
      }
    }

    if (width != null && text.length() < width) {
      int padding = width - text.length();
      String pad = (zero != null ? "0" : fill).repeat(padding);
      if (">".equals(align) || (align == null && zero != null)) {
        text = pad + text;
      } else if ("^".equals(align)) {
        int left = padding / 2;
        text = pad.substring(0, left) + text + pad.substring(left);
      } else {
        text = text + pad;
      }
    }
    return text;
  }

  private static final int MAX_REGEX_INPUT = 1024 * 1024 * 10;
  private static final int MAX_PATTERN_LENGTH = 500;
  private static final List<Pattern> DANGEROUS = List.of(
      Pattern.compile("\\([^)]*\\+[^)]*\\)\\+"),
      Pattern.compile("\\([^)]*\\*[^)]*\\)\\+"),
      Pattern.compile("\\([^)]*\\+[^)]*\\)\\*"),
      Pattern.compile("\\([^)]*\\*[^)]*\\)\\*"));

  /**
   * Pattern replacement, with the limits the original puts on it.
   *
   * <p>The pattern comes from whoever wrote the notification body, and a nested quantifier can
   * take longer than the age of the universe on an input the page controls. The limits are
   * therefore part of the behaviour: an oversized input is truncated, an oversized pattern is
   * refused, and a pattern with a nested quantifier is refused, each returning the input
   * unchanged rather than failing the check.
   */
  public static String regexReplace(String value, String pattern, String replacement, int count) {
    String text = value;
    if (text.length() > MAX_REGEX_INPUT) {
      text = text.substring(0, MAX_REGEX_INPUT);
    }
    if (pattern.length() > MAX_PATTERN_LENGTH) {
      return text;
    }
    for (Pattern dangerous : DANGEROUS) {
      if (dangerous.matcher(pattern).find()) {
        return text;
      }
    }
    try {
      Pattern compiled = io.akka.changedetection.text.PyRegex.compile(pattern);
      Matcher matcher = compiled.matcher(text);
      String javaReplacement = pythonReplacementToJava(replacement);
      if (count <= 0) {
        return matcher.replaceAll(javaReplacement);
      }
      StringBuilder sb = new StringBuilder();
      int replaced = 0;
      while (replaced < count && matcher.find()) {
        matcher.appendReplacement(sb, javaReplacement);
        replaced++;
      }
      matcher.appendTail(sb);
      return sb.toString();
    } catch (RuntimeException e) {
      return text;
    }
  }

  private static String pythonReplacementToJava(String replacement) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '\\' && i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
        sb.append('$').append(replacement.charAt(i + 1));
        i++;
      } else if (c == '$') {
        sb.append("\\$");
      } else if (c == '\\' && i + 1 < replacement.length()) {
        sb.append('\\').append(replacement.charAt(i + 1));
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
