package io.akka.changedetection.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.akka.changedetection.text.HtmlTools;
import io.akka.changedetection.text.JsonFilter;
import io.akka.changedetection.text.PythonText;
import io.akka.changedetection.text.SequenceMatcher;
import io.akka.changedetection.text.XPathFilter;
import java.util.List;

/** Every question a probe may ask the rebuild, keyed by the name the probe uses. */
final class ProbeOps {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProbeOps() {}

  static JsonNode dispatch(String op, JsonNode args) {
    switch (op) {
      case "html_to_text":
        return MAPPER.valueToTree(
            HtmlTools.htmlToText(
                args.get("html").asText(),
                args.path("render_anchor_tag_content").asBoolean(false),
                args.path("is_rss").asBoolean(false)));

      case "strip_ignore_text":
        return MAPPER.valueToTree(
            HtmlTools.stripIgnoreText(
                args.get("content").asText(), ProbeOracle.toStringList(args.get("wordlist"))));

      case "strip_ignore_text_lines":
        return MAPPER.valueToTree(
            HtmlTools.ignoredLineNumbers(
                args.get("content").asText(), ProbeOracle.toStringList(args.get("wordlist"))));

      case "include_filters":
        return MAPPER.valueToTree(
            HtmlTools.includeFilters(
                args.get("filter").asText(),
                args.get("html").asText(),
                args.path("append_pretty_line_formatting").asBoolean(false)));

      case "element_removal":
        return MAPPER.valueToTree(
            HtmlTools.elementRemoval(
                ProbeOracle.toStringList(args.get("selectors")), args.get("html").asText()));

      case "xpath_filter":
        return MAPPER.valueToTree(
            XPathFilter.xpathFilter(
                args.get("filter").asText(),
                args.get("html").asText(),
                args.path("append_pretty_line_formatting").asBoolean(false),
                args.path("is_xml").asBoolean(false)));

      case "xpath1_filter":
        return MAPPER.valueToTree(
            XPathFilter.xpath1Filter(
                args.get("filter").asText(),
                args.get("html").asText(),
                args.path("append_pretty_line_formatting").asBoolean(false),
                args.path("is_xml").asBoolean(false)));

      case "extract_json_as_string":
        return MAPPER.valueToTree(
            JsonFilter.extractJsonAsString(
                args.get("content").asText(),
                args.get("filter").asText(),
                args.hasNonNull("ensure_type") ? args.get("ensure_type").asText() : null,
                false));

      case "cdata_to_text":
        return MAPPER.valueToTree(HtmlTools.cdataInDocumentToText(args.get("content").asText()));

      case "workarounds_for_obfuscations":
        return MAPPER.valueToTree(
            HtmlTools.workaroundsForObfuscations(args.get("content").asText()));

      case "has_ldjson_product_info":
        return MAPPER.valueToTree(HtmlTools.hasLdJsonProductInfo(args.get("content").asText()));

      case "extract_title":
        return MAPPER.valueToTree(HtmlTools.extractTitle(args.get("content").asText()));

      case "get_triggered_text":
        return MAPPER.valueToTree(
            HtmlTools.getTriggeredText(
                args.get("content").asText(), ProbeOracle.toStringList(args.get("trigger_text"))));

      case "splitlines":
        return MAPPER.valueToTree(PythonText.splitLines(args.get("content").asText()));

      case "opcodes": {
        List<String> a = ProbeOracle.toStringList(args.get("a"));
        List<String> b = ProbeOracle.toStringList(args.get("b"));
        SequenceMatcher matcher =
            new SequenceMatcher(SequenceMatcher.whitespaceLineIsJunk(), a, b);
        ArrayNode out = MAPPER.createArrayNode();
        for (SequenceMatcher.OpCode c : matcher.getOpCodes()) {
          ArrayNode row = MAPPER.createArrayNode();
          row.add(c.tag());
          row.add(c.i1());
          row.add(c.i2());
          row.add(c.j1());
          row.add(c.j2());
          out.add(row);
        }
        return out;
      }

      case "unified_diff":
        return MAPPER.valueToTree(
            SequenceMatcher.unifiedDiff(
                ProbeOracle.toStringList(args.get("a")), ProbeOracle.toStringList(args.get("b"))));

      case "include_filters_then_text":
        return MAPPER.valueToTree(
            HtmlTools.htmlToText(
                HtmlTools.includeFilters(
                    args.get("filter").asText(), args.get("html").asText(), true),
                false,
                false));

      case "element_removal_then_text":
        return MAPPER.valueToTree(
            HtmlTools.htmlToText(
                HtmlTools.elementRemoval(
                    ProbeOracle.toStringList(args.get("selectors")), args.get("html").asText()),
                false,
                false));

      case "xpath_filter_then_text":
        return MAPPER.valueToTree(
            HtmlTools.htmlToText(
                XPathFilter.xpathFilter(
                    args.get("filter").asText(), args.get("html").asText(), true, false),
                false,
                false));

      case "xpath1_filter_then_text":
        return MAPPER.valueToTree(
            HtmlTools.htmlToText(
                XPathFilter.xpath1Filter(
                    args.get("filter").asText(), args.get("html").asText(), true, false),
                false,
                false));

      default:
        return ProbeOpsPart2.dispatch(op, args);
    }
  }
}
