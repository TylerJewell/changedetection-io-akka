package io.akka.changedetection.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The published description of the programmatic interface, and the field lists derived from it.
 *
 * <p>Which fields a caller may set is not written out anywhere in the code -- it is whatever the
 * description says is not read-only, and the description is the file served at
 * {@code /api/v1/full-spec}. Deriving the lists from that file rather than restating them keeps
 * the two from drifting, which is the whole reason the original does it this way.
 *
 * <p>A processor may add fields of its own. Each contributes a description alongside its own
 * code; those are merged in here the same way, so a caller creating a price watch may set the
 * price fields without them being named twice.
 */
public final class ApiSpec {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static volatile Map<String, Object> merged;
  private static volatile String mergedText;

  private ApiSpec() {}

  /** The whole description, with every processor's additions folded in. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> spec() {
    if (merged != null) {
      return merged;
    }
    synchronized (ApiSpec.class) {
      if (merged != null) {
        return merged;
      }
      Map<String, Object> base = read("changedetection/api/api-spec.yaml");
      Map<String, Object> components = (Map<String, Object>) base.get("components");
      Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

      for (String processor : List.of("restock_diff")) {
        Map<String, Object> extra = read("changedetection/api/" + processor + "-api.yaml");
        Map<String, Object> extraComponents = (Map<String, Object>) extra.get("components");
        if (extraComponents == null) {
          continue;
        }
        Map<String, Object> extraSchemas = (Map<String, Object>) extraComponents.get("schemas");
        if (extraSchemas == null) {
          continue;
        }
        schemas.putAll(extraSchemas);
        String key = "processor_config_" + processor;
        if (extraSchemas.containsKey(key)) {
          Map<String, Object> watchBase = (Map<String, Object>) schemas.get("WatchBase");
          Map<String, Object> properties = (Map<String, Object>) watchBase.get("properties");
          properties.put(key, Map.of("$ref", "#/components/schemas/" + key));
        }
      }
      merged = base;
      return merged;
    }
  }

  /** The description as a caller receives it: one document, in the language it is written in. */
  public static String specText() {
    if (mergedText == null) {
      synchronized (ApiSpec.class) {
        if (mergedText == null) {
          try {
            mergedText = YAML.writeValueAsString(spec());
          } catch (Exception e) {
            mergedText = "";
          }
        }
      }
    }
    return mergedText;
  }

  /** Every property a schema declares, including the ones it inherits. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> properties(String schemaName) {
    Map<String, Object> components = (Map<String, Object>) spec().get("components");
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaName);
    Map<String, Object> out = new LinkedHashMap<>();
    if (schema == null) {
      return out;
    }
    Object allOf = schema.get("allOf");
    if (allOf instanceof List<?> parts) {
      for (Object part : parts) {
        Map<String, Object> item = (Map<String, Object>) part;
        Object ref = item.get("$ref");
        if (ref != null) {
          String name = String.valueOf(ref);
          out.putAll(properties(name.substring(name.lastIndexOf('/') + 1)));
        }
        Object own = item.get("properties");
        if (own instanceof Map<?, ?> map) {
          out.putAll((Map<String, Object>) map);
        }
      }
      return out;
    }
    Object own = schema.get("properties");
    if (own instanceof Map<?, ?> map) {
      out.putAll((Map<String, Object>) map);
    }
    return out;
  }

  /** The fields of a schema the service sets itself, which a caller may not. */
  @SuppressWarnings("unchecked")
  public static Set<String> readOnly(String schemaName) {
    Set<String> out = new LinkedHashSet<>();
    for (Map.Entry<String, Object> entry : properties(schemaName).entrySet()) {
      if (entry.getValue() instanceof Map<?, ?> property
          && Boolean.TRUE.equals(((Map<String, Object>) property).get("readOnly"))) {
        out.add(entry.getKey());
      }
    }
    return out;
  }

  /** The type a query parameter carrying this property should be read as. */
  @SuppressWarnings("unchecked")
  public static String typeOf(Object property) {
    if (!(property instanceof Map<?, ?> map)) {
      return "string";
    }
    Map<String, Object> schema = (Map<String, Object>) map;
    Object type = schema.get("type");
    if (type instanceof List<?> alternatives) {
      for (Object option : alternatives) {
        if (!"null".equals(String.valueOf(option))) {
          return String.valueOf(option);
        }
      }
      return "string";
    }
    if (type != null) {
      return String.valueOf(type);
    }
    Object anyOf = schema.get("anyOf");
    if (anyOf instanceof List<?> options) {
      for (Object option : options) {
        Object optionType = ((Map<String, Object>) option).get("type");
        if (optionType != null && !"null".equals(String.valueOf(optionType))) {
          return String.valueOf(optionType);
        }
      }
    }
    return "string";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(String resource) {
    try (InputStream in = ApiSpec.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        return new LinkedHashMap<>();
      }
      byte[] bytes = in.readAllBytes();
      Map<String, Object> parsed =
          YAML.readValue(new String(bytes, StandardCharsets.UTF_8), Map.class);
      return parsed == null ? new LinkedHashMap<>() : parsed;
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }
}
