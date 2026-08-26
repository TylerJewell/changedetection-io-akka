package io.akka.changedetection.jinja;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The questions a template may ask about a value with {@code is}. */
public final class Tests {

  private Tests() {}

  /** One test: the value, then whatever the template passed alongside it. */
  public interface Test {
    boolean apply(Object value, List<Object> arguments);
  }

  public static Map<String, Test> standard() {
    Map<String, Test> tests = new LinkedHashMap<>();
    tests.put("defined", (value, arguments) -> value != PyValue.UNDEFINED);
    tests.put("undefined", (value, arguments) -> value == PyValue.UNDEFINED);
    tests.put("none", (value, arguments) -> value == null);
    tests.put("boolean", (value, arguments) -> value instanceof Boolean);
    tests.put("true", (value, arguments) -> Boolean.TRUE.equals(value));
    tests.put("false", (value, arguments) -> Boolean.FALSE.equals(value));
    tests.put("string", (value, arguments) -> value instanceof CharSequence);
    tests.put("number",
        (value, arguments) -> value instanceof Number || value instanceof Boolean);
    tests.put("integer", (value, arguments) -> PyValue.isIntegral(value));
    tests.put("float", (value, arguments) -> value instanceof Double || value instanceof Float);
    tests.put("mapping", (value, arguments) -> value instanceof Map<?, ?>);
    tests.put("sequence",
        (value, arguments) -> value instanceof Collection<?> || value instanceof CharSequence
            || value instanceof Object[]);
    tests.put("iterable",
        (value, arguments) -> value instanceof Collection<?> || value instanceof CharSequence
            || value instanceof Map<?, ?> || value instanceof Object[]);
    tests.put("callable", (value, arguments) -> value instanceof PyValue.Callable);
    tests.put("odd", (value, arguments) -> Filters.toLong(value, 0L) % 2 != 0);
    tests.put("even", (value, arguments) -> Filters.toLong(value, 0L) % 2 == 0);
    tests.put("divisibleby",
        (value, arguments) -> Filters.toLong(value, 0L) % Filters.toLong(arguments.get(0), 1L) == 0);
    tests.put("sameas", (value, arguments) -> value == arguments.get(0));
    tests.put("escaped", (value, arguments) -> value instanceof PyValue.Markup);
    tests.put("in", (value, arguments) -> PyValue.contains(arguments.get(0), value));
    tests.put("eq", (value, arguments) -> PyValue.equal(value, arguments.get(0)));
    tests.put("equalto", (value, arguments) -> PyValue.equal(value, arguments.get(0)));
    tests.put("ne", (value, arguments) -> !PyValue.equal(value, arguments.get(0)));
    tests.put("lt", (value, arguments) -> PyValue.compare(value, arguments.get(0)) < 0);
    tests.put("le", (value, arguments) -> PyValue.compare(value, arguments.get(0)) <= 0);
    tests.put("gt", (value, arguments) -> PyValue.compare(value, arguments.get(0)) > 0);
    tests.put("ge", (value, arguments) -> PyValue.compare(value, arguments.get(0)) >= 0);
    tests.put("upper",
        (value, arguments) -> PyValue.asString(value).equals(
            PyValue.asString(value).toUpperCase(java.util.Locale.ROOT)));
    tests.put("lower",
        (value, arguments) -> PyValue.asString(value).equals(
            PyValue.asString(value).toLowerCase(java.util.Locale.ROOT)));
    return tests;
  }
}
