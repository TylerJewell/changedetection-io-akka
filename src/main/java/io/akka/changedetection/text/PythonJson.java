package io.akka.changedetection.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/**
 * JSON written the way Python writes it.
 *
 * <p>The original stores the output of a JSON filter as the snapshot text, so the exact
 * serialisation -- indent width, where the spaces go, how a float is spelled -- is part of the
 * content whose checksum decides "changed". Two serialisers that both produce valid JSON
 * produce different checksums.
 */
public final class PythonJson {

  public static final ObjectMapper MAPPER = new ObjectMapper();

  private PythonJson() {}

  /** json.dumps(value, indent=4, ensure_ascii=False). */
  public static String dumpsIndented(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    write(node, sb, 4, 0);
    return sb.toString();
  }

  /** json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False). */
  public static String dumpsSortedIndent2(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    write(sortKeys(node), sb, 2, 0);
    return sb.toString();
  }

  /** json.dumps(value) with no indent and the writer's own spacing between items. */
  public static String dumpsCompact(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    writeCompact(node, sb);
    return sb.toString();
  }

  /** json.dumps(value, sort_keys=True) with no indent, as used for a configuration hash. */
  public static String dumpsSortedCompact(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    writeCompact(sortKeys(node), sb);
    return sb.toString();
  }

  private static JsonNode sortKeys(JsonNode node) {
    if (node.isObject()) {
      java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        sorted.put(e.getKey(), sortKeys(e.getValue()));
      }
      com.fasterxml.jackson.databind.node.ObjectNode out = MAPPER.createObjectNode();
      for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
        out.set(e.getKey(), e.getValue());
      }
      return out;
    }
    if (node.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
      for (JsonNode child : node) {
        out.add(sortKeys(child));
      }
      return out;
    }
    return node;
  }

  private static void write(JsonNode node, StringBuilder sb, int indent, int level) {
    if (node == null || node.isNull()) {
      sb.append("null");
    } else if (node.isObject()) {
      if (node.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{\n");
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      boolean first = true;
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        if (!first) {
          sb.append(",\n");
        }
        first = false;
        pad(sb, indent * (level + 1));
        escape(e.getKey(), sb);
        sb.append(": ");
        write(e.getValue(), sb, indent, level + 1);
      }
      sb.append('\n');
      pad(sb, indent * level);
      sb.append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        sb.append("[]");
        return;
      }
      sb.append("[\n");
      boolean first = true;
      for (JsonNode child : node) {
        if (!first) {
          sb.append(",\n");
        }
        first = false;
        pad(sb, indent * (level + 1));
        write(child, sb, indent, level + 1);
      }
      sb.append('\n');
      pad(sb, indent * level);
      sb.append(']');
    } else {
      writeScalar(node, sb);
    }
  }

  private static void writeCompact(JsonNode node, StringBuilder sb) {
    if (node == null || node.isNull()) {
      sb.append("null");
    } else if (node.isObject()) {
      sb.append('{');
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      boolean first = true;
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        if (!first) {
          sb.append(", ");
        }
        first = false;
        escape(e.getKey(), sb);
        sb.append(": ");
        writeCompact(e.getValue(), sb);
      }
      sb.append('}');
    } else if (node.isArray()) {
      sb.append('[');
      boolean first = true;
      for (JsonNode child : node) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        writeCompact(child, sb);
      }
      sb.append(']');
    } else {
      writeScalar(node, sb);
    }
  }

  private static void writeScalar(JsonNode node, StringBuilder sb) {
    if (node.isTextual()) {
      escape(node.textValue(), sb);
    } else if (node.isBoolean()) {
      sb.append(node.booleanValue() ? "true" : "false");
    } else if (node.isIntegralNumber()) {
      sb.append(node.bigIntegerValue().toString());
    } else if (node.isNumber()) {
      sb.append(floatRepr(node.doubleValue()));
    } else {
      sb.append(node.asText());
    }
  }

  private static void pad(StringBuilder sb, int n) {
    for (int i = 0; i < n; i++) {
      sb.append(' ');
    }
  }

  private static void escape(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  /**
   * A float spelled the way the original spells it: the shortest decimal that reads back to
   * the same value, laid out as a plain number between one ten-thousandth and ten thousand
   * million million, and in exponent form outside that -- with the exponent always signed and
   * at least two digits.
   *
   * <p>A JSON filter's output is stored as the compared text, so the spelling of a number in it
   * is content. A price written 1e+20 on one side and 1.0E20 on the other is a change on every
   * check, forever.
   */
  public static String floatRepr(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? "Infinity" : "-Infinity";
    }
    if (value == 0.0) {
      return (1 / value < 0 ? "-0.0" : "0.0");
    }

    boolean negative = value < 0;
    double magnitude = Math.abs(value);
    String digits = null;
    int decimalExponent = 0;
    for (int precision = 1; precision <= 17; precision++) {
      BigDecimal rounded =
          new BigDecimal(magnitude).round(new java.math.MathContext(precision));
      if (rounded.doubleValue() == magnitude) {
        BigDecimal stripped = rounded.stripTrailingZeros();
        digits = stripped.unscaledValue().toString();
        decimalExponent = digits.length() - stripped.scale();
        break;
      }
    }
    if (digits == null) {
      return Double.toString(value);
    }

    StringBuilder sb = new StringBuilder();
    if (negative) {
      sb.append('-');
    }
    if (decimalExponent > -4 && decimalExponent <= 17) {
      if (decimalExponent <= 0) {
        sb.append("0.");
        sb.append("0".repeat(-decimalExponent));
        sb.append(digits);
      } else if (decimalExponent >= digits.length()) {
        sb.append(digits);
        sb.append("0".repeat(decimalExponent - digits.length()));
        sb.append(".0");
      } else {
        sb.append(digits, 0, decimalExponent);
        sb.append('.');
        sb.append(digits, decimalExponent, digits.length());
      }
      return sb.toString();
    }

    sb.append(digits.charAt(0));
    if (digits.length() > 1) {
      sb.append('.').append(digits, 1, digits.length());
    }
    int exponent = decimalExponent - 1;
    sb.append('e').append(exponent < 0 ? '-' : '+');
    String exponentDigits = Integer.toString(Math.abs(exponent));
    if (exponentDigits.length() < 2) {
      sb.append('0');
    }
    sb.append(exponentDigits);
    return sb.toString();
  }
}
