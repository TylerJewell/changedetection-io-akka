package io.akka.changedetection.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.changedetection.conditions.Levenshtein;
import io.akka.changedetection.conditions.PriceParser;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.CheckOutcome;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.ProcessorExceptions;
import io.akka.changedetection.processors.RssTools;
import io.akka.changedetection.processors.StreamType;
import io.akka.changedetection.processors.TextJsonDiffProcessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The last of the probe surface; new questions land here. */
final class ProbeOpsPart4 {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProbeOpsPart4() {}

  @SuppressWarnings("unchecked")
  static JsonNode dispatch(String op, JsonNode args) {
    switch (op) {
      case "check_sequence": {
        ProbeStore store = new ProbeStore();
        JsonNode settings = args.path("settings");
        if (settings.isObject()) {
          store.applicationSettings().putAll(MAPPER.convertValue(settings, Map.class));
        }
        Map<String, Object> watchFields =
            args.path("watch").isObject()
                ? MAPPER.convertValue(args.get("watch"), Map.class)
                : new LinkedHashMap<>();
        Watch watch = store.addWatch("probe-uuid", watchFields);
        JsonNode tags = args.path("tag_overrides");
        if (tags.isObject()) {
          store.tagOverrides.put("probe-uuid", MAPPER.convertValue(tags, Map.class));
        }

        TextJsonDiffProcessor processor = new TextJsonDiffProcessor(store);
        ArrayNode answers = MAPPER.createArrayNode();
        long timestamp = 1000;

        for (JsonNode step : args.get("bodies")) {
          String body = step.isObject() ? step.get("body").asText() : step.asText();
          String contentType =
              step.isObject() && step.has("content_type")
                  ? step.get("content_type").asText()
                  : "text/html";
          ObjectNode answer = MAPPER.createObjectNode();
          try {
            Fetched fetched = new Fetched(body, contentType, 200);
            CheckOutcome outcome = processor.run(watch, fetched);
            answer.put("verdict", outcome.changed() ? "changed" : "unchanged");
            answer.put("contents", outcome.contents());
            answer.put("previous_md5",
                String.valueOf(outcome.updates().getOrDefault("previous_md5", "")));
            watch.update(outcome.updates());
            watch.resetEditedFlag();
            if (outcome.changed() || watch.historyCount() == 0) {
              store.saveSnapshot("probe-uuid", timestamp, outcome.contents());
            }
          } catch (ProcessorExceptions.ChecksumWasTheSame e) {
            answer.put("verdict", "skipped");
            watch.resetEditedFlag();
          } catch (ProcessorExceptions.FilterNotFound e) {
            answer.put("verdict", "filter-not-found");
          } catch (ProcessorExceptions.ReplyWithContentButNoText e) {
            answer.put("verdict", "no-text");
          } catch (RuntimeException e) {
            answer.put("verdict", "error");
            answer.put("error", e.getClass().getSimpleName());
          }
          answers.add(answer);
          timestamp += 60;
        }
        return answers;
      }

      case "stream_type": {
        StreamType type =
            StreamType.guess(args.get("content_type").asText(), args.get("content").asText());
        ObjectNode out = MAPPER.createObjectNode();
        out.put("is_pdf", type.isPdf);
        out.put("is_json", type.isJson);
        out.put("is_html", type.isHtml);
        out.put("is_plaintext", type.isPlaintext);
        out.put("is_rss", type.isRss);
        out.put("is_csv", type.isCsv);
        out.put("is_xml", type.isXml);
        out.put("is_yaml", type.isYaml);
        return out;
      }

      case "rss_format_items":
        return MAPPER.valueToTree(RssTools.formatItems(args.get("content").asText()));

      case "extracted_number": {
        Double value = PriceParser.parse(args.get("text").asText());
        return MAPPER.valueToTree(value);
      }

      case "levenshtein": {
        ObjectNode out = MAPPER.createObjectNode();
        String a = args.get("a").asText();
        String b = args.get("b").asText();
        out.put("distance", Levenshtein.distance(a, b));
        int places = args.path("round").asInt(9);
        double scale = Math.pow(10, places);
        out.put("ratio", Math.round(Levenshtein.ratio(a, b) * scale) / scale);
        return out;
      }

      default:
        {
        JsonNode answer = ProbeOpsBench.dispatch(op, args);
        if (answer == null) {
          throw new IllegalArgumentException("unknown probe op: " + op);
        }
        return answer;
      }
    }
  }
}
