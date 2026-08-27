package io.akka.changedetection.web;

import io.akka.changedetection.application.CheckRunner;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.model.WatchDefaults;
import io.akka.changedetection.processors.CheckOutcome;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.TextJsonDiffProcessor;
import io.akka.changedetection.text.HtmlTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the operator's unsaved filter settings would do to the page already fetched.
 *
 * <p>The last stored document is put through the real processor three times: once with the
 * settings as typed, once with no settings at all, and once with the extraction rules removed.
 * The first two are the two panes the page shows side by side. The third exists because
 * extraction rewrites lines, so the line numbers an ignore rule matches have to be worked out
 * against the text before it ran or they would point at the wrong lines.
 *
 * <p>Nothing here is written back. The watch is a copy, its checksum is never stored, and the
 * processor is asked to reprocess whatever it already saw.
 */
public final class FilterPreview {

  /** What the page needs to draw both panes and the three kinds of highlight. */
  public record Result(
      String afterFilter,
      String beforeFilter,
      List<Integer> triggerLineNumbers,
      List<Integer> ignoreLineNumbers,
      List<Integer> blockedLineNumbers,
      Map<String, Object> llmEvaluation,
      double durationSeconds) {

    public Map<String, Object> asMap() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("after_filter", afterFilter);
      out.put("before_filter", beforeFilter);
      out.put("blocked_line_numbers", blockedLineNumbers);
      out.put("duration", durationSeconds);
      out.put("ignore_line_numbers", ignoreLineNumbers);
      out.put("llm_evaluation", llmEvaluation);
      out.put("trigger_line_numbers", triggerLineNumbers);
      return out;
    }
  }

  private FilterPreview() {}

  public static Result run(Store store, String watchUuid, Map<String, Object> formValues) {
    long began = System.nanoTime();
    String afterFilter = "";
    String beforeFilter = "";
    String preExtract = "";

    WatchState state = store.watch(watchUuid);
    Watch temporary = state.exists() ? state.asWatch() : Watch.create(watchUuid);
    temporary.update(formValues);

    if (state.exists() && !state.history().isEmpty()) {
      long latest = state.history().get(state.history().size() - 1);
      String document = store.sideStore(watchUuid, "html-" + latest);
      if (document == null) {
        document = store.snapshot(watchUuid, latest);
      }
      if (document != null) {
        Fetched fetched = new Fetched();
        fetched.content = document;
        fetched.rawContent = document.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fetched.headers.put(
            "content-type", temporary.fields().string("content-type", "text/html"));

        TextJsonDiffProcessor processor =
            new TextJsonDiffProcessor(new CheckRunner.ProcessorEnvironment(store));

        Watch bare = Watch.create(watchUuid);
        bare.update(Map.of("url", temporary.fields().string("url", "")));

        Watch withoutExtraction = state.exists() ? state.asWatch() : Watch.create(watchUuid);
        withoutExtraction.update(formValues);
        withoutExtraction.update(Map.of("extract_text", new ArrayList<String>()));

        afterFilter = textOf(processor, temporary, fetched);
        beforeFilter = textOf(processor, bare, fetched);
        preExtract = textOf(processor, withoutExtraction, fetched);
      }
    }

    List<Integer> triggerLines = new ArrayList<>();
    List<Integer> ignoreLines = new ArrayList<>();
    List<Integer> blockedLines = new ArrayList<>();
    try {
      triggerLines =
          HtmlTools.ignoredLineNumbers(afterFilter, temporary.fields().strings("trigger_text"));
    } catch (RuntimeException e) {
      beforeFilter = "Error: " + e.getMessage();
    }
    try {
      List<String> toIgnore = new ArrayList<>(temporary.fields().strings("ignore_text"));
      toIgnore.addAll(stringsOf(store.application().get("global_ignore_text")));
      ignoreLines =
          ignoreLineNumbers(preExtract, toIgnore, temporary.fields().strings("extract_text"));
    } catch (RuntimeException e) {
      beforeFilter = "Error: " + e.getMessage();
    }
    try {
      List<String> blocked =
          new ArrayList<>(temporary.fields().strings("text_should_not_be_present"));
      blocked.addAll(stringsOf(store.application().get("text_should_not_be_present")));
      blockedLines = HtmlTools.ignoredLineNumbers(afterFilter, blocked);
    } catch (RuntimeException e) {
      beforeFilter = "Error: " + e.getMessage();
    }

    Map<String, Object> evaluation = null;
    try {
      if (!afterFilter.isBlank() && !"Empty content".equals(afterFilter.strip())) {
        evaluation =
            io.akka.changedetection.llm.Evaluator.previewExtract(
                temporary, store.llmSurroundings(), afterFilter);
      }
    } catch (RuntimeException e) {
      // Advisory: a model that will not answer must never stop the preview being shown.
    }

    double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
    return new Result(
        afterFilter, beforeFilter, triggerLines, ignoreLines, blockedLines, evaluation, seconds);
  }

  /**
   * Where an ignore rule's lines fall, worked out against the text before extraction ran.
   *
   * <p>Extraction rewrites a line to whatever it pulled out of it, so an ignore rule matched
   * against the result would miss lines it does match, and the highlight would be wrong on
   * exactly the watches that use both features.
   */
  static List<Integer> ignoreLineNumbers(
      String preExtractText, List<String> ignorePatterns, List<String> extractPatterns) {
    if (preExtractText == null || preExtractText.isEmpty() || ignorePatterns.isEmpty()) {
      return new ArrayList<>();
    }
    List<Integer> matched = HtmlTools.ignoredLineNumbers(preExtractText, ignorePatterns);
    if (matched.isEmpty() || extractPatterns.isEmpty()) {
      return matched;
    }
    java.util.Set<Integer> ignored = new java.util.LinkedHashSet<>(matched);

    // Extraction is replayed one input line at a time, because each match it emits becomes an
    // output line of its own: the count of matches a line produced is how far the numbering
    // moves, and only lines that were ignored contribute numbers to the answer.
    List<Integer> out = new ArrayList<>();
    int outputLine = 0;
    String[] lines = preExtractText.split("\r?\n", -1);
    for (int index = 0; index < lines.length; index++) {
      boolean isIgnored = ignored.contains(index + 1);
      String extracted =
          io.akka.changedetection.processors.ContentTransformer.extractByRegex(
              lines[index], extractPatterns);
      int emitted = 0;
      for (int at = 0; at < extracted.length(); at++) {
        if (extracted.charAt(at) == '\n') {
          emitted++;
        }
      }
      for (int match = 0; match < emitted; match++) {
        outputLine++;
        if (isIgnored) {
          out.add(outputLine);
        }
      }
    }
    return out;
  }

  private static String textOf(
      TextJsonDiffProcessor processor, Watch watch, Fetched fetched) {
    String text;
    try {
      CheckOutcome outcome = processor.run(watch, fetched, true);
      text = outcome.contents() == null ? "" : outcome.contents();
    } catch (io.akka.changedetection.processors.ProcessorExceptions.FilterNotFound e) {
      text = "Filter not found in HTML: " + e.getMessage();
    } catch (io.akka.changedetection.processors.ProcessorExceptions.ReplyWithContentButNoText e) {
      text = "Filter found but no text (empty result)";
    } catch (RuntimeException e) {
      text = "Error: " + e.getMessage();
    }
    return text.isBlank() ? "Empty content" : text;
  }

  private static List<String> stringsOf(Object value) {
    List<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
    }
    return out;
  }

  /** Fields a preview submission may set; anything else is ignored rather than written. */
  public static Map<String, Object> formValues(Map<String, List<String>> submitted) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : submitted.entrySet()) {
      String name = entry.getKey();
      if (WatchDefaults.SYSTEM_MANAGED.contains(name) || name.startsWith("__")) {
        continue;
      }
      List<String> values = entry.getValue();
      if (LIST_FIELDS.contains(name)) {
        List<String> lines = new ArrayList<>();
        for (String value : values) {
          for (String line : value.split("\r?\n")) {
            if (!line.strip().isEmpty()) {
              lines.add(line.strip());
            }
          }
        }
        out.put(name, lines);
      } else if (BOOLEAN_FIELDS.contains(name)) {
        out.put(name, Fields.truthy(values.isEmpty() ? "" : values.get(0)));
      } else {
        out.put(name, values.isEmpty() ? "" : values.get(values.size() - 1));
      }
    }
    return out;
  }

  private static final List<String> LIST_FIELDS =
      List.of(
          "ignore_text",
          "trigger_text",
          "text_should_not_be_present",
          "extract_text",
          "subtractive_selectors",
          "tags",
          "notification_urls");

  private static final List<String> BOOLEAN_FIELDS =
      List.of(
          "check_unique_lines",
          "remove_duplicate_lines",
          "sort_text_alphabetically",
          "trim_text_whitespace",
          "filter_text_added",
          "filter_text_replaced",
          "filter_text_removed",
          "render_anchor_tag_content",
          "strip_ignored_lines",
          "ignore_whitespace");
}
