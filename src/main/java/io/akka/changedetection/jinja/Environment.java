package io.akka.changedetection.jinja;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Where templates are found, what a template may call, and how much it may return.
 *
 * <p>The return limit is not tidiness. A notification body is written by whoever configured the
 * watch and is rendered against page content the watch fetched, so a template like
 * {@code {{ content * 100000 }}} turns a page into as much memory as the machine has. The
 * original caps the rendered result and so does this.
 */
public final class Environment {

  /** How a template name is turned into its source. */
  public interface Loader {
    String load(String name);
  }

  public static final int DEFAULT_MAX_RETURN_BYTES = 1024 * 1024 * 10;

  private final Loader loader;
  private final Map<String, Node.Template> cache = new ConcurrentHashMap<>();
  private final Map<String, Filters.Filter> filters = new HashMap<>(Filters.standard());
  private final Map<String, Tests.Test> tests = new HashMap<>(Tests.standard());
  private final Map<String, Object> globals = new LinkedHashMap<>();

  private int maxReturnBytes = DEFAULT_MAX_RETURN_BYTES;
  private String defaultTimezone = "UTC";
  private String datetimeFormat = "%a, %d %b %Y %H:%M:%S";
  private boolean autoescape = false;

  public Environment() {
    this(name -> {
      throw new JinjaException("no template loader is configured, cannot load " + name);
    });
  }

  public Environment(Loader loader) {
    this.loader = loader;
    installGlobals();
  }

  private void installGlobals() {
    globals.put("range", (PyValue.Callable) (positional, keyword) -> {
      long start = 0;
      long stop;
      long step = 1;
      if (positional.size() == 1) {
        stop = Filters.toLong(positional.get(0), 0L);
      } else {
        start = Filters.toLong(positional.get(0), 0L);
        stop = Filters.toLong(positional.get(1), 0L);
        if (positional.size() > 2) {
          step = Filters.toLong(positional.get(2), 1L);
        }
      }
      List<Object> out = new ArrayList<>();
      if (step > 0) {
        for (long i = start; i < stop; i += step) {
          out.add(i);
        }
      } else if (step < 0) {
        for (long i = start; i > stop; i += step) {
          out.add(i);
        }
      }
      return out;
    });
    globals.put("dict", (PyValue.Callable) (positional, keyword) -> {
      Map<String, Object> out = new LinkedHashMap<>();
      if (!positional.isEmpty() && positional.get(0) instanceof Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          out.put(PyValue.asString(entry.getKey()), entry.getValue());
        }
      }
      out.putAll(keyword);
      return out;
    });
    globals.put("namespace", (PyValue.Callable) (positional, keyword) -> {
      Map<String, Object> out = new LinkedHashMap<>(keyword);
      return out;
    });
    globals.put("enumerate", (PyValue.Callable) (positional, keyword) -> {
      List<Object> out = new ArrayList<>();
      long index = positional.size() > 1 ? Filters.toLong(positional.get(1), 0L) : 0;
      for (Object item : PyValue.iterate(positional.get(0))) {
        out.add(new PyValue.Tuple(index++, item));
      }
      return out;
    });
    globals.put("__discard__", (PyValue.Callable) (positional, keyword) -> PyValue.UNDEFINED);
    globals.put("_", (PyValue.Callable) (positional, keyword) ->
        translate(PyValue.asString(positional.get(0)), keyword));
    globals.put("gettext", globals.get("_"));
    globals.put("ngettext", (PyValue.Callable) (positional, keyword) -> {
      long count = Filters.toLong(positional.get(2), 0L);
      String chosen = count == 1 ? PyValue.asString(positional.get(0))
          : PyValue.asString(positional.get(1));
      Map<String, Object> withCount = new LinkedHashMap<>(keyword);
      withCount.put("num", count);
      return translate(chosen, withCount);
    });
    globals.put("pgettext", (PyValue.Callable) (positional, keyword) ->
        translate(PyValue.asString(positional.get(1)), keyword));
  }

  private Object translate(String message, Map<String, Object> keyword) {
    String translated = translator == null ? message : translator.apply(message);
    if (keyword.isEmpty()) {
      return translated;
    }
    return Filters.percentFormat(translated, keyword);
  }

  private Function<String, String> translator;

  /** How a message is looked up; the identity function leaves the source language in place. */
  public void setTranslator(Function<String, String> translator) {
    this.translator = translator;
  }

  public void putGlobal(String name, Object value) {
    globals.put(name, value);
  }

  public Object global(String name) {
    return globals.containsKey(name) ? globals.get(name) : PyValue.UNDEFINED;
  }

  public Map<String, Object> globals() {
    return globals;
  }

  public void putFilter(String name, Filters.Filter filter) {
    filters.put(name, filter);
  }

  public void putTest(String name, Tests.Test test) {
    tests.put(name, test);
  }

  public void setAutoescape(boolean autoescape) {
    this.autoescape = autoescape;
  }

  public boolean autoescape() {
    return autoescape;
  }

  public void setMaxReturnBytes(int maxReturnBytes) {
    this.maxReturnBytes = maxReturnBytes;
  }

  public void setDefaultTimezone(String defaultTimezone) {
    this.defaultTimezone = defaultTimezone;
  }

  public String defaultTimezone() {
    return defaultTimezone;
  }

  public String datetimeFormat() {
    return datetimeFormat;
  }

  public void setDatetimeFormat(String datetimeFormat) {
    this.datetimeFormat = datetimeFormat;
  }

  public Object applyFilter(String name, Object value, List<Object> positional,
      Map<String, Object> keyword) {
    Filters.Filter filter = filters.get(name);
    if (filter == null) {
      throw new JinjaException("no filter named '" + name + "'");
    }
    return filter.apply(this, value, positional, keyword);
  }

  public boolean hasFilter(String name) {
    return filters.containsKey(name);
  }

  public boolean applyTest(String name, Object value, List<Object> arguments) {
    Tests.Test test = tests.get(name);
    if (test == null) {
      throw new JinjaException("no test named '" + name + "'");
    }
    return test.apply(value, arguments);
  }

  public Node.Template template(String name) {
    return cache.computeIfAbsent(name, key -> TemplateParser.parse(loader.load(key), key));
  }

  public void clearCache() {
    cache.clear();
  }

  /** Renders a named template. */
  public String render(String name, Map<String, Object> context) {
    return cap(new Interpreter(this).render(template(name), context));
  }

  /** Renders a template given as a string, which is what a notification body is. */
  public String renderString(String source, Map<String, Object> context) {
    return cap(new Interpreter(this).render(TemplateParser.parse(source, "<string>"), context));
  }

  private String cap(String rendered) {
    return rendered.length() > maxReturnBytes ? rendered.substring(0, maxReturnBytes) : rendered;
  }

  /** The moment a {@code now} tag resolves to, in the zone the tag names. */
  public ZonedDateTime now(String timezone) {
    String zone = timezone == null || timezone.isBlank() ? defaultTimezone : timezone.strip();
    try {
      return ZonedDateTime.ofInstant(Instant.now(), ZoneId.of(zone));
    } catch (Exception e) {
      throw new JinjaException("unknown timezone '" + zone + "'");
    }
  }
}
