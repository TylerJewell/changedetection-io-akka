package io.akka.changedetection.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A watch's fields, held as a map rather than as declared members.
 *
 * <p>This follows the original, and the reason is not convenience. A watch, a tag and the
 * global settings share one field set: a tag overrides a watch by holding the same keys, and
 * the settings supply the fallback for the same keys again. Processor plugins add keys of
 * their own, and the API accepts and returns whatever the schema names. Declaring seventy
 * members three times over would make each of those a separate place to keep in step, and the
 * resolution chain -- watch, then tag, then global -- would have to be written per field
 * rather than once.
 */
public final class Fields {

  public static final String USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT = "System default";
  public static final String CONDITIONS_MATCH_LOGIC_DEFAULT = "ALL";

  private final Map<String, Object> values;

  public Fields() {
    this.values = new LinkedHashMap<>();
  }

  public Fields(Map<String, Object> values) {
    this.values = new LinkedHashMap<>(values);
  }

  public Map<String, Object> asMap() {
    return values;
  }

  public Fields copy() {
    return new Fields(deepCopy(values));
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> deepCopy(Map<String, Object> source) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      out.put(entry.getKey(), deepCopyValue(entry.getValue()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Object deepCopyValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return deepCopy((Map<String, Object>) map);
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object item : list) {
        out.add(deepCopyValue(item));
      }
      return out;
    }
    return value;
  }

  public boolean has(String key) {
    return values.containsKey(key);
  }

  public Object get(String key) {
    return values.get(key);
  }

  public void put(String key, Object value) {
    values.put(key, value);
  }

  public void remove(String key) {
    values.remove(key);
  }

  public void putAll(Map<String, Object> other) {
    values.putAll(other);
  }

  public String string(String key) {
    Object value = values.get(key);
    return value == null ? null : String.valueOf(value);
  }

  public String string(String key, String fallback) {
    String value = string(key);
    return value == null ? fallback : value;
  }

  /**
   * A flag read the way the original reads one.
   *
   * <p>Three of the original's flags are deliberately three-valued -- unset, on, off -- because
   * unset means "take the global setting" and off means "override the global setting to off".
   * Folding an unset flag to false would silently override the global one.
   */
  public Boolean tristate(String key) {
    Object value = values.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    return truthy(value);
  }

  public boolean bool(String key) {
    return bool(key, false);
  }

  public boolean bool(String key, boolean fallback) {
    Boolean value = tristate(key);
    return value == null ? fallback : value;
  }

  public Integer integer(String key) {
    Object value = values.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.valueOf(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public int integer(String key, int fallback) {
    Integer value = integer(key);
    return value == null ? fallback : value;
  }

  public Double number(String key) {
    Object value = values.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return Double.valueOf(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public long longValue(String key, long fallback) {
    Object value = values.get(key);
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).strip());
    } catch (Exception e) {
      return fallback;
    }
  }

  @SuppressWarnings("unchecked")
  public List<String> strings(String key) {
    Object value = values.get(key);
    if (value == null) {
      return new ArrayList<>();
    }
    if (value instanceof List<?> list) {
      List<String> out = new ArrayList<>();
      for (Object item : list) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
      return out;
    }
    List<String> out = new ArrayList<>();
    out.add(String.valueOf(value));
    return out;
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> maps(String key) {
    Object value = values.get(key);
    List<Map<String, Object>> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          out.add((Map<String, Object>) map);
        }
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> map(String key) {
    Object value = values.get(key);
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return new LinkedHashMap<>();
  }

  /** Truthiness as the original's own conversion defines it. */
  public static boolean truthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (value instanceof String s) {
      String lowered = s.strip().toLowerCase(java.util.Locale.ROOT);
      if (lowered.equals("y") || lowered.equals("yes") || lowered.equals("t")
          || lowered.equals("true") || lowered.equals("on") || lowered.equals("1")) {
        return true;
      }
      if (lowered.equals("n") || lowered.equals("no") || lowered.equals("f")
          || lowered.equals("false") || lowered.equals("off") || lowered.equals("0")) {
        return false;
      }
      return !s.isEmpty();
    }
    if (value instanceof List<?> list) {
      return !list.isEmpty();
    }
    if (value instanceof Map<?, ?> map) {
      return !map.isEmpty();
    }
    return true;
  }
}
