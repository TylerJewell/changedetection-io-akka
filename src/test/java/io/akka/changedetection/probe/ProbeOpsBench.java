package io.akka.changedetection.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.changedetection.application.Schedule;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.TextJsonDiffProcessor;
import io.akka.changedetection.text.HtmlTools;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The questions only the benchmark asks: an order, a window at an instant, a draw, a timing.
 *
 * <p>The timings are taken inside this process rather than across the pipe the probe speaks
 * over, because a figure measured through the pipe is a figure about the pipe.
 */
final class ProbeOpsBench {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How long a timed window aims to run, matching what the Python side aims at. */
  private static final long WINDOW_TARGET_NANOS = 50_000_000L;

  private ProbeOpsBench() {}

  @SuppressWarnings("unchecked")
  static JsonNode dispatch(String op, JsonNode args) {
    switch (op) {
      case "arrival_order": {
        // Both systems order the overdue by when each was last checked. Every row here
        // carries the same moment, so what comes back is whatever the sort does with a tie.
        List<Map<String, Object>> rows =
            MAPPER.convertValue(args.get("rows"), List.class);
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        ordered.sort(
            Comparator.comparingLong(row -> ((Number) row.get("last_checked")).longValue()));
        ArrayNode out = MAPPER.createArrayNode();
        for (Map<String, Object> row : ordered) {
          out.addObject().put("verdict", String.valueOf(row.get("uuid")));
        }
        return out;
      }

      case "due_decision": {
        // The scheduler's own arithmetic, with everything around it left out because each
        // of those is a different rule with its own workload. The floor and the shared
        // interval are the ones this service ships with.
        long minimum = 3;
        long systemInterval = 3600;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode one : args.get("cases")) {
          Map<String, Object> interval = MAPPER.convertValue(one.get("interval"), Map.class);
          boolean paused = one.get("paused").asBoolean();
          boolean useDefault = one.get("useDefault").asBoolean();
          long elapsed = one.get("elapsed").asLong();
          ObjectNode answer = out.addObject();
          answer.put("case", one.get("case").asText());
          if (paused) {
            answer.put("verdict", false);
            continue;
          }
          long threshold = useDefault ? systemInterval : Watch.thresholdSeconds(interval);
          answer.put("verdict", elapsed >= threshold && elapsed >= minimum);
        }
        return out;
      }

      case "schedule_window": {
        ArrayNode out = MAPPER.createArrayNode();
        long offset = args.path("offset").asLong(0);
        for (JsonNode one : args.get("cases")) {
          Map<String, Object> schedule =
              MAPPER.convertValue(one.get("schedule"), Map.class);
          ZonedDateTime moment =
              ZonedDateTime.of(
                      java.time.LocalDateTime.parse(one.get("at").asText()), ZoneId.of("UTC"))
                  .plusSeconds(offset);
          ObjectNode answer = out.addObject();
          answer.put("case", one.get("case").asText());
          try {
            answer.put("verdict", Schedule.isWithin(schedule, "UTC", moment));
          } catch (RuntimeException e) {
            answer.put("verdict", "error: " + e.getClass().getSimpleName());
          }
        }
        return out;
      }

      case "jitter_draws": {
        int draws = args.get("draws").asInt();
        double jitter = args.get("jitterSeconds").asDouble();
        Random random = new Random();
        int below = 0;
        int atOrAbove = 0;
        for (int index = 0; index < draws; index++) {
          double drawn = io.akka.changedetection.application.Scheduler.drawJitter(jitter, random);
          if (drawn < 0) {
            below++;
          } else {
            atOrAbove++;
          }
        }
        ObjectNode counts = MAPPER.createObjectNode();
        counts.put("below zero", below);
        counts.put("at or above zero", atOrAbove);
        ArrayNode out = MAPPER.createArrayNode();
        out.addObject().set("verdict", counts);
        return out;
      }

      case "bench_timings": {
        ObjectNode timing = MAPPER.createObjectNode();
        timing.set("one-check", timeOneCheck());
        timing.set("markup-to-text", timeMarkupToText());
        ObjectNode out = MAPPER.createObjectNode();
        out.set("timing", timing);
        return out;
      }

      default:
        return null;
    }
  }

  private static final String PAGE_ONE =
      "<html><body><h1>Title</h1><p>Body one</p><p>price: $10.99</p></body></html>";

  private static final String PAGE_TWO =
      "<html><body><h1>Title</h1><p>Body two</p><p>price: $12.99</p></body></html>";

  private static ObjectNode timeOneCheck() {
    ProbeStore store = new ProbeStore();
    Watch watch = store.addWatch("timing-uuid", new LinkedHashMap<>());
    TextJsonDiffProcessor processor = new TextJsonDiffProcessor(store);
    String[] bodies = {PAGE_ONE, PAGE_TWO};
    int[] counter = {0};

    return window(
        () -> {
          // The body changes every call, so the compiler cannot prove the call constant and
          // lift it out of the loop, and the result is read so it cannot prove it dead.
          String body = bodies[counter[0]++ % bodies.length];
          try {
            return processor.run(watch, new Fetched(body, "text/html", 200)).contents().length();
          } catch (RuntimeException e) {
            return e.getClass().getName().length();
          }
        });
  }

  private static ObjectNode timeMarkupToText() {
    String[] bodies = {PAGE_ONE, PAGE_TWO};
    int[] counter = {0};
    return window(
        () -> HtmlTools.htmlToText(bodies[counter[0]++ % bodies.length], false, false).length());
  }

  /** One call, timed over windows sized from a pilot, reported as the median of five. */
  private static ObjectNode window(java.util.function.IntSupplier call) {
    long accumulated = 0;

    int pilotRepetitions = 1;
    long pilot = 0;
    while (pilotRepetitions < (1 << 22)) {
      long began = System.nanoTime();
      for (int index = 0; index < pilotRepetitions; index++) {
        accumulated += call.getAsInt();
      }
      pilot = System.nanoTime() - began;
      if (pilot >= 1000) {
        break;
      }
      pilotRepetitions *= 8;
    }

    int repetitions = 1;
    if (pilot > 0) {
      long perCall = Math.max(1, pilot / pilotRepetitions);
      repetitions = (int) Math.max(1, Math.min(20_000_000L, WINDOW_TARGET_NANOS / perCall));
    }

    long[] lengths = new long[5];
    for (int window = 0; window < lengths.length; window++) {
      long began = System.nanoTime();
      for (int index = 0; index < repetitions; index++) {
        accumulated += call.getAsInt();
      }
      lengths[window] = System.nanoTime() - began;
    }
    Arrays.sort(lengths);
    long median = lengths[lengths.length / 2];

    ObjectNode out = MAPPER.createObjectNode();
    out.put("repetitions", repetitions);
    out.put("windows", lengths.length);
    out.put("windowNanos", median);
    out.put("nanosPerRun", (double) median / repetitions);
    // Read once so nothing in the loop can be proven unused.
    out.put("accumulated", accumulated);
    return out;
  }
}
