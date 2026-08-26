package io.akka.changedetection.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.jinja.Environment;
import java.util.LinkedHashMap;
import java.util.Map;

/** The rest again; each subsystem adds its questions here as it lands. */
final class ProbeOpsPart3 {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProbeOpsPart3() {}

  static JsonNode dispatch(String op, JsonNode args) {
    switch (op) {
      case "jinja_render": {
        Environment environment = new Environment();
        Map<String, Object> context = new LinkedHashMap<>();
        JsonNode given = args.get("context");
        if (given != null && given.isObject()) {
          context.putAll(MAPPER.convertValue(given, Map.class));
        }
        return MAPPER.valueToTree(
            environment.renderString(args.get("template").asText(), context));
      }

      default:
        return ProbeOpsPart4.dispatch(op, args);
    }
  }
}
