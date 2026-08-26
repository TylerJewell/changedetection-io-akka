package io.akka.changedetection.processors;

import io.akka.changedetection.diff.DiffRenderer;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.text.HtmlTools;
import io.akka.changedetection.text.JsonFilter;
import io.akka.changedetection.text.PythonJson;
import io.akka.changedetection.text.PythonText;
import io.akka.changedetection.text.XPathFilter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decision: given a fetched page, has this watch changed?
 *
 * <p>The value of the product is not that it notices a page changed -- that is one comparison
 * -- but that it declines to tell you about the changes you do not care about. So most of what
 * follows is about not reporting: rules that block a change, text that is removed before the
 * comparison, and a shortcut that skips the whole thing when nothing could have moved.
 *
 * <p>Two orderings inside are load-bearing and easy to get wrong. Subtractive selectors run
 * before include filters, because a selector written against an ancestor cannot match once the
 * include filter has thrown the ancestor away. And the ignored-line removal is snapshotted
 * before the extract rules run, because an extract rule rewrites a line and an ignore rule
 * written against the original wording would then no longer match it.
 */
public final class TextJsonDiffProcessor {

  /** What the processor needs from outside a single watch. */
  public interface Environment extends FilterConfig.Context {
    /** The checksum of the last raw document this watch fetched, if one is remembered. */
    String lastRawContentChecksum(String watchUuid);

    void updateLastRawContentChecksum(String watchUuid, String checksum);

    /** The stored text of a snapshot. */
    String snapshot(String watchUuid, long timestamp);

    /** The text of the last fetch before any ignore rule was applied to it. */
    String lastFetchedTextBeforeFilters(String watchUuid);

    void saveLastFetchedTextBeforeFilters(String watchUuid, String text);

    /** Whether a rule set on this watch is met, which is the conditions feature. */
    boolean conditionsAllow(Watch watch, String text);

    /** A document converted to markup, where the deployment can do that. */
    String pdfToHtml(byte[] rawContent);

    /** Whether the deployment permits the riskier expressions in a JSON filter. */
    default boolean allowRiskyJqExpressions() {
      return Fields.truthy(System.getenv("JQ_ALLOW_RISKY_EXPRESSIONS"));
    }
  }

  private final Environment environment;

  public TextJsonDiffProcessor(Environment environment) {
    this.environment = environment;
  }

  public CheckOutcome run(Watch watch, Fetched fetched) {
    return run(watch, fetched, false);
  }

  public CheckOutcome run(Watch watch, Fetched fetched, boolean forceReprocess) {
    boolean changedDetected = false;

    String currentRawChecksum = PythonText.md5Hex(fetched.rawContent);
    FilterConfig filterConfig = new FilterConfig(watch, environment);
    String currentFilterConfigHash = filterConfig.hash();

    String lastRawChecksum = environment.lastRawContentChecksum(watch.uuid());
    String lastFilterConfigHash = watch.fields().string("last_filter_config_hash");
    if (!forceReprocess
        && !watch.wasEdited()
        && lastRawChecksum != null
        && lastRawChecksum.equals(currentRawChecksum)
        && lastFilterConfigHash != null
        && !lastFilterConfigHash.isEmpty()
        && lastFilterConfigHash.equals(currentFilterConfigHash)) {
      throw new ProcessorExceptions.ChecksumWasTheSame();
    }

    Map<String, Object> application = environment.application();
    String contentTypeHeader = fetched.contentType();
    if (contentTypeHeader == null) {
      contentTypeHeader = "text/html";
    }
    StreamType streamType = StreamType.guess(contentTypeHeader, fetched.content);

    Map<String, Object> updates = new LinkedHashMap<>();
    updates.put("last_notification_error", false);
    updates.put("last_error", false);
    updates.put("content-type", contentTypeHeader);

    environment.updateLastRawContentChecksum(watch.uuid(), currentRawChecksum);
    updates.put("last_filter_config_hash", currentFilterConfigHash);

    String content = fetched.content;

    if (streamType.isRss) {
      if (Fields.truthy(application.get("rss_reader_mode"))) {
        content = RssTools.formatItems(content);
        // Once a feed has been laid out as ordinary markup, it is ordinary markup: an include
        // filter written as a selector then works on it, which is the whole point of the mode.
        streamType.isRss = false;
        streamType.isHtml = true;
        fetched.content = content;
      } else {
        content = HtmlTools.cdataInDocumentToText(content);
      }
    }

    if (watch.isPdf() || streamType.isPdf) {
      content = environment.pdfToHtml(fetched.rawContent);
      streamType.isHtml = true;
    }

    if (streamType.isJson && !filterConfig.hasIncludeJsonFilters()) {
      content = reformatJson(content, environment.allowRiskyJqExpressions());
    }

    if (streamType.isHtml) {
      content = HtmlTools.workaroundsForObfuscations(content);
      updates.put("has_ldjson_price_data", HtmlTools.hasLdJsonProductInfo(content));
    }

    String htmlContent = content;

    if (filterConfig.hasSubtractiveSelectors()) {
      htmlContent = HtmlTools.elementRemoval(filterConfig.subtractiveSelectors(), htmlContent);
    }
    if (filterConfig.hasIncludeFilters()) {
      htmlContent = applyIncludeFilters(watch, filterConfig, htmlContent, streamType);
    }

    String strippedText;
    if (watch.isSourceTypeUrl()) {
      strippedText = htmlContent;
    } else if (streamType.isPlaintext) {
      strippedText = htmlContent;
    } else if (streamType.isHtml || streamType.isRss) {
      boolean renderAnchors = Fields.truthy(application.get("render_anchor_tag_content"));
      strippedText = HtmlTools.htmlToText(htmlContent, renderAnchors, streamType.isRss);
    } else {
      strippedText = htmlContent;
    }

    if (watch.fields().bool("trim_text_whitespace")) {
      strippedText = ContentTransformer.trimWhitespace(strippedText);
    }

    String textBeforeIgnoredFilter = strippedText;

    if (watch.hasSpecialDiffFilterOptionsSet() && !watch.history().isEmpty()) {
      String renderedDiff = applyDiffFiltering(watch, strippedText, textBeforeIgnoredFilter);
      if (renderedDiff == null) {
        String checksum =
            checksum(textBeforeIgnoredFilter, true);
        Map<String, Object> only = new LinkedHashMap<>();
        only.put("previous_md5", checksum);
        return CheckOutcome.of(false, only, textBeforeIgnoredFilter);
      }
      strippedText = renderedDiff;
    }

    boolean emptyPagesAreAChange = Fields.truthy(application.get("empty_pages_are_a_change"));
    if (!streamType.isJson && !emptyPagesAreAChange && strippedText.strip().isEmpty()) {
      throw new ProcessorExceptions.ReplyWithContentButNoText(
          fetched.statusCode, filterConfig.hasIncludeFilters(), htmlContent);
    }

    updates.put("last_check_status", fetched.statusCode);

    // The ignore rules are applied here, before the extract rules run, and the result is kept
    // alongside. An extract rule rewrites a line -- turning "v.1.2.1" into "1.2.1" -- and an
    // ignore rule written against the original wording would no longer match it, so a change
    // confined to an ignored line would start counting.
    String textForChecksumming = null;
    if (!filterConfig.ignoreText().isEmpty()) {
      textForChecksumming = HtmlTools.stripIgnoreText(strippedText, filterConfig.ignoreText());
    }

    if (!filterConfig.extractLinesContaining().isEmpty()) {
      strippedText =
          ContentTransformer.extractLinesContaining(strippedText, filterConfig.extractLinesContaining());
      if (textForChecksumming != null) {
        textForChecksumming =
            ContentTransformer.extractLinesContaining(
                textForChecksumming, filterConfig.extractLinesContaining());
      }
    }

    if (!filterConfig.extractText().isEmpty()) {
      strippedText = ContentTransformer.extractByRegex(strippedText, filterConfig.extractText());
      if (textForChecksumming != null) {
        textForChecksumming =
            ContentTransformer.extractByRegex(textForChecksumming, filterConfig.extractText());
      }
    }

    if (watch.fields().bool("remove_duplicate_lines")) {
      strippedText = ContentTransformer.removeDuplicateLines(strippedText);
      if (textForChecksumming != null) {
        textForChecksumming = ContentTransformer.removeDuplicateLines(textForChecksumming);
      }
    }

    if (watch.fields().bool("sort_text_alphabetically")) {
      strippedText = ContentTransformer.sortAlphabetically(strippedText);
      if (textForChecksumming != null) {
        textForChecksumming = ContentTransformer.sortAlphabetically(textForChecksumming);
      }
    }

    if (textForChecksumming == null) {
      textForChecksumming = strippedText;
    } else {
      Boolean stripIgnoredLines = watch.fields().tristate("strip_ignored_lines");
      if (stripIgnoredLines == null) {
        stripIgnoredLines = Fields.truthy(application.get("strip_ignored_lines"));
      }
      if (stripIgnoredLines) {
        strippedText = textForChecksumming;
      }
    }

    boolean ignoreWhitespace = Fields.truthy(application.get("ignore_whitespace"));
    String fetchedMd5 = checksum(textForChecksumming, ignoreWhitespace);

    boolean blocked = false;
    if (triggerBlocks(textForChecksumming, filterConfig.triggerText())) {
      blocked = true;
    }
    if (forbiddenPresent(strippedText, filterConfig.textShouldNotBePresent())) {
      blocked = true;
    }
    if (!environment.conditionsAllow(watch, strippedText)) {
      blocked = true;
    }

    if (blocked) {
      changedDetected = false;
    } else {
      Object previous = watch.fields().get("previous_md5");
      String previousMd5 = previous instanceof String s ? s : null;
      if (previousMd5 == null || !previousMd5.equals(fetchedMd5)) {
        changedDetected = true;
      }
      updates.put("previous_md5", fetchedMd5);
      if (previousMd5 == null || previousMd5.isEmpty()) {
        watch.fields().put("previous_md5", fetchedMd5);
      }
    }

    if (changedDetected && watch.fields().bool("check_unique_lines")) {
      boolean hasUnique =
          watch.linesContainSomethingUniqueComparedToHistory(
              PythonText.splitLines(strippedText),
              ignoreWhitespace,
              timestamp -> environment.snapshot(watch.uuid(), timestamp));
      if (!hasUnique) {
        changedDetected = false;
      }
    }

    return CheckOutcome.of(changedDetected, updates, strippedText);
  }

  private String applyIncludeFilters(
      Watch watch, FilterConfig filterConfig, String content, StreamType streamType) {
    StringBuilder filtered = new StringBuilder();
    boolean prettyLines = !watch.isSourceTypeUrl();
    for (String rule : filterConfig.includeFilters()) {
      if (rule.isEmpty()) {
        continue;
      }
      if (rule.charAt(0) == '/' || rule.startsWith("xpath:")) {
        filtered.append(
            XPathFilter.xpathFilter(
                rule.replace("xpath:", ""),
                content,
                prettyLines,
                streamType.isRss || streamType.isXml));
      } else if (rule.startsWith("xpath1:")) {
        filtered.append(
            XPathFilter.xpath1Filter(
                rule.replace("xpath1:", ""),
                content,
                prettyLines,
                streamType.isRss || streamType.isXml));
      } else if (startsWithJsonPrefix(rule)) {
        filtered.append(
            JsonFilter.extractJsonAsString(
                content, rule, null, environment.allowRiskyJqExpressions()));
      } else {
        filtered.append(HtmlTools.includeFilters(rule, content, prettyLines));
      }
    }
    if (filtered.toString().strip().isEmpty()) {
      throw new ProcessorExceptions.FilterNotFound(filterConfig.includeFilters());
    }
    return filtered.toString();
  }

  private static boolean startsWithJsonPrefix(String rule) {
    for (String prefix : JsonFilter.JSON_FILTER_PREFIXES) {
      if (rule.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The text reduced to only the kinds of change this watch cares about.
   *
   * <p>Returns null where there were none, which is a decision rather than an absence: the
   * check records a checksum taken over the whole text so a later change is still noticed, but
   * reports nothing this time.
   */
  private String applyDiffFiltering(Watch watch, String strippedText, String textBeforeFilter) {
    DiffRenderer.Options options = new DiffRenderer.Options();
    options.includeEqual = false;
    options.includeAdded = watch.fields().bool("filter_text_added", true);
    options.includeRemoved = watch.fields().bool("filter_text_removed", true);
    options.includeReplaced = watch.fields().bool("filter_text_replaced", true);
    options.includeChangeTypePrefix = false;

    // Where no earlier text was kept -- the first check with this rule on -- the newest stored
    // snapshot stands in for it, so the rule takes effect immediately rather than reporting the
    // whole page as added once.
    String previous = environment.lastFetchedTextBeforeFilters(watch.uuid());
    if (previous == null && !watch.history().isEmpty()) {
      previous = environment.snapshot(watch.uuid(), watch.history().get(watch.history().size() - 1));
    }
    String rendered = DiffRenderer.render(previous == null ? "" : previous, strippedText, options);

    environment.saveLastFetchedTextBeforeFilters(watch.uuid(), textBeforeFilter);

    if (rendered.isEmpty() && !strippedText.isEmpty()) {
      return null;
    }
    return rendered;
  }

  private static boolean triggerBlocks(String content, List<String> triggerPatterns) {
    if (triggerPatterns.isEmpty()) {
      return false;
    }
    // With a trigger configured the change is blocked until the trigger appears, so the
    // question asked is "did nothing match", not "did something match".
    return HtmlTools.ignoredLineNumbers(content, triggerPatterns).isEmpty();
  }

  private static boolean forbiddenPresent(String content, List<String> patterns) {
    if (patterns.isEmpty()) {
      return false;
    }
    return !HtmlTools.ignoredLineNumbers(content, patterns).isEmpty();
  }

  public static String checksum(String text, boolean ignoreWhitespace) {
    String subject = ignoreWhitespace ? PythonText.translateWhitespaceAway(text) : text;
    return PythonText.md5Hex(subject.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A JSON document reformatted so that a reordering of its keys is not a change.
   *
   * <p>A document the server labelled JSON but that will not parse is kept as it came, because
   * failing the whole check would hide the page from the operator rather than tell them about
   * it.
   */
  static String reformatJson(String rawContent, boolean allowRiskyJq) {
    String extracted;
    try {
      extracted = JsonFilter.extractJsonAsString(rawContent, "json:$", null, allowRiskyJq);
    } catch (HtmlTools.JsonNotFound e) {
      return rawContent;
    }
    if (extracted.isEmpty()) {
      return rawContent;
    }
    try {
      return PythonJson.dumpsSortedIndent2(PythonJson.MAPPER.readTree(extracted));
    } catch (Exception e) {
      return extracted;
    }
  }
}
