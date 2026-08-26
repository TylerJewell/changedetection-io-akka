package io.akka.changedetection.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.diff.DiffRenderer;
import io.akka.changedetection.diff.Tokenizers;

/** The rest of the probe surface, added as each subsystem of the rebuild lands. */
final class ProbeOpsPart2 {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProbeOpsPart2() {}

  static JsonNode dispatch(String op, JsonNode args) {
    switch (op) {
      case "render_diff": {
        DiffRenderer.Options options = new DiffRenderer.Options();
        options.includeEqual = args.path("include_equal").asBoolean(false);
        options.includeRemoved = args.path("include_removed").asBoolean(true);
        options.includeAdded = args.path("include_added").asBoolean(true);
        options.includeReplaced = args.path("include_replaced").asBoolean(true);
        options.includeChangeTypePrefix = args.path("include_change_type_prefix").asBoolean(true);
        options.patchFormat = args.path("patch_format").asBoolean(false);
        options.wordDiff = args.path("word_diff").asBoolean(true);
        options.contextLines = args.path("context_lines").asInt(0);
        options.caseInsensitive = args.path("case_insensitive").asBoolean(false);
        options.ignoreJunk = args.path("ignore_junk").asBoolean(false);
        options.tokenizer = args.path("tokenizer").asText("words_and_html");
        return MAPPER.valueToTree(
            DiffRenderer.render(args.get("previous").asText(), args.get("newest").asText(), options));
      }

      case "tokenize":
        return MAPPER.valueToTree(
            Tokenizers.byName(args.path("tokenizer").asText("words_and_html"),
                args.get("text").asText()));

      case "extract_changed_from":
        return MAPPER.valueToTree(DiffRenderer.extractChangedFrom(args.get("raw").asText()));

      case "extract_changed_to":
        return MAPPER.valueToTree(DiffRenderer.extractChangedTo(args.get("raw").asText()));

      case "inline_word_diff": {
        DiffRenderer.InlineDiff inline =
            DiffRenderer.renderInlineWordDiff(
                args.get("before").asText(),
                args.get("after").asText(),
                args.path("ignore_junk").asBoolean(false),
                args.path("tokenizer").asText("words_and_html"),
                args.path("include_change_type_prefix").asBoolean(true));
        return MAPPER.valueToTree(new Object[] {inline.text(), inline.hasChanges()});
      }

      case "nested_line_diff":
        return MAPPER.valueToTree(
            DiffRenderer.renderNestedLineDiff(
                args.get("before").asText(),
                args.get("after").asText(),
                args.path("ignore_junk").asBoolean(false),
                args.path("tokenizer").asText("words_and_html")));

      default:
        return ProbeOpsPart3.dispatch(op, args);
    }
  }
}
