package io.akka.changedetection.llm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The messages sent to the model, built from what the operator asked for. */
public final class PromptBuilder {

  /** How much of the current page goes alongside the difference. */
  public static final int SNAPSHOT_CONTEXT_CHARS = 30_000;

  private static final Pattern AGO =
      Pattern.compile("^\\d+\\s+\\w+\\s+ago$", Pattern.CASE_INSENSITIVE);

  private PromptBuilder() {}

  /**
   * Marks lines that appear on both sides of a difference as moved rather than new.
   *
   * <p>A page that reorders its items produces a difference in which the same text is both
   * added and removed; without the mark the model reports the whole page as rewritten.
   */
  static String annotateMovedLines(String diffText) {
    String[] lines = diffText.split("\n", -1);
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    for (String line : lines) {
      if (line.startsWith("+") && !line.substring(1).strip().isEmpty()) {
        added.add(line.substring(1).strip().toLowerCase(Locale.ROOT));
      }
      if (line.startsWith("-") && !line.substring(1).strip().isEmpty()) {
        removed.add(line.substring(1).strip().toLowerCase(Locale.ROOT));
      }
    }
    Set<String> moved = new LinkedHashSet<>(added);
    moved.retainAll(removed);
    if (moved.isEmpty()) {
      return diffText;
    }
    List<String> result = new ArrayList<>();
    for (String line : lines) {
      if (line.startsWith("+") || line.startsWith("-")) {
        String bare = line.substring(1).strip();
        if (moved.contains(bare.toLowerCase(Locale.ROOT)) || AGO.matcher(bare).lookingAt()) {
          result.add("~" + line.substring(1));
          continue;
        }
      }
      result.add(line);
    }
    return String.join("\n", result);
  }

  public static String evalPrompt(
      String intent, String diff, String currentSnapshot, String url, String title) {
    List<String> parts = new ArrayList<>();
    if (!url.isEmpty()) {
      parts.add("URL: " + url);
    }
    if (!title.isEmpty()) {
      parts.add("Page title: " + title);
    }
    parts.add("Intent: " + intent);
    if (currentSnapshot != null && !currentSnapshot.isEmpty()) {
      String excerpt = Bm25Trim.trimToRelevant(currentSnapshot, intent, SNAPSHOT_CONTEXT_CHARS);
      if (!excerpt.isEmpty()) {
        parts.add("\nCurrent page state (relevant excerpt):\n" + excerpt);
      }
    }
    parts.add("\nWhat changed (diff):\n" + diff);
    return String.join("\n", parts);
  }

  public static String evalSystemPrompt() {
    return "You are a precise, reliable website-change evaluator for a monitoring tool.\n"
        + "Your job is to read a unified diff and decide whether it matches a user's stated"
        + " intent.\n"
        + "Accuracy is critical — false positives waste the user's attention; false negatives"
        + " miss what they care about.\n\n"
        + "Diff format:\n"
        + "- Lines starting with '+' are newly ADDED content\n"
        + "- Lines starting with '-' are REMOVED content\n"
        + "- Lines starting with ' ' (space) are unchanged context\n\n"
        + "Respond with ONLY a JSON object — no markdown, no explanation outside it:\n"
        + "{\"important\": true/false, \"summary\": \"one sentence describing the relevant"
        + " change, or why it doesn't match\"}\n\n"
        + "Rules:\n"
        + "- important=true ONLY when the diff clearly and specifically matches the intent — be"
        + " strict\n"
        + "- Pay close attention to direction: an intent about price drops means removed (-)"
        + " prices and added (+) lower prices\n"
        + "- The user's intent always wins. If the intent explicitly asks about timestamps,"
        + " numbers, counters, thresholds, or any specific value (e.g. 'when the timestamp is"
        + " greater than 1778599592', 'when stock count > 5'), evaluate the diff against that"
        + " intent — do NOT dismiss it as cosmetic.\n"
        + "- Otherwise: empty, trivial, or genuinely cosmetic diffs (heartbeat timestamps, view"
        + " counters, whitespace, navigation tweaks) default to important=false\n"
        + "- For numeric comparisons in the intent, parse the values explicitly and compare them"
        + " — do not eyeball or round\n"
        + "- If the same text appears in both removed (-) and added (+) lines the content has"
        + " likely just shifted or been reordered. Treat pure reordering as important=false"
        + " unless the intent explicitly asks about order or position.\n"
        + "- Use OR logic when the intent lists multiple triggers — any one matching is"
        + " sufficient\n"
        + "- When uncertain whether a change truly matches, prefer important=false and explain"
        + " why in the summary\n"
        + "- Summary must be in the same language as the intent\n"
        + "- If important=false, the summary must clearly explain what changed and why it does"
        + " not match";
  }

  public static String previewPrompt(String intent, String content, String url, String title) {
    List<String> parts = new ArrayList<>();
    if (!url.isEmpty()) {
      parts.add("URL: " + url);
    }
    if (!title.isEmpty()) {
      parts.add("Page title: " + title);
    }
    parts.add("Intent / question: " + intent);
    parts.add("\nPage content:\n" + content.substring(0, Math.min(6_000, content.length())));
    return String.join("\n", parts);
  }

  public static String previewSystemPrompt() {
    return "You are a precise, detail-oriented web page content analyst for a website monitoring"
        + " tool.\n"
        + "Given the user's intent or question and the current page content, extract and"
        + " directly answer what the intent is looking for. Never guess or paraphrase — report"
        + " only what the page actually contains.\n\n"
        + "Respond with ONLY a JSON object — no markdown, no explanation outside it:\n"
        + "{\"found\": true/false, \"answer\": \"concise direct answer or extraction\"}\n\n"
        + "Rules:\n"
        + "- found=true when the page clearly contains something relevant to the intent\n"
        + "- answer must directly address the intent with specific values where possible (e.g."
        + " for 'current price?' → '$149.99', not 'a price is shown')\n"
        + "- answer must be in the same language as the intent\n"
        + "- Keep answer brief — one or two sentences maximum\n"
        + "- If found=false, briefly state what the page contains instead";
  }

  /**
   * The message for a plain-language summary.
   *
   * <p>The current page is deliberately not included: given a page excerpt the model reports
   * unchanged parts of it as changes, because it cannot tell which of what it was shown is new.
   * The difference already carries three lines of surrounding context, which is enough.
   */
  public static String changeSummaryPrompt(
      String diff, String customPrompt, String currentSnapshot, String url, String title) {
    List<String> parts = new ArrayList<>();
    if (!url.isEmpty()) {
      parts.add("URL: " + url);
    }
    if (!title.isEmpty()) {
      parts.add("Page title: " + title);
    }
    parts.add("Instructions: " + customPrompt);
    parts.add("\nWhat changed (diff):\n" + annotateMovedLines(diff));
    return String.join("\n", parts);
  }

  public static String changeSummarySystemPrompt() {
    return "You analyse a unified-diff document showing how a monitored web page changed, and"
        + " produce exactly the output the user asks for.\n\n"
        + "Rules for reading the diff:\n"
        + "- Lines starting with + are genuinely new content.\n"
        + "- Lines starting with - are genuinely removed content.\n"
        + "- Lines starting with ~ have been PRE-IDENTIFIED as moved/reordered or trivial — the"
        + " same text exists on both sides of the diff, or the line is a standalone timestamp."
        + " Do NOT treat ~ lines as added or removed.\n\n"
        + "Accuracy: only report what the +/- lines actually contain. Never invent details,"
        + " never speculate, never add information that isn't in the diff.\n\n"
        + "Follow the user's instructions exactly — including the requested output format (plain"
        + " text, JSON, Markdown, single value, etc.), structure, language, and length. Do not"
        + " add preamble, meta-commentary, or self-introduction. Produce only the output the"
        + " user asked for — nothing before it, nothing after it.";
  }

  public static String setupPrompt(String intent, String snapshotText, String url) {
    String excerpt = Bm25Trim.trimToRelevant(snapshotText, intent, 4_000);
    List<String> parts = new ArrayList<>();
    if (!url.isEmpty()) {
      parts.add("URL: " + url);
    }
    parts.add("Intent: " + intent);
    parts.add("\nPage content excerpt:\n" + excerpt);
    return String.join("\n", parts);
  }

  public static String setupSystemPrompt() {
    return "You help configure a website change monitor.\n"
        + "Given a monitoring intent and a sample of the page content, decide if a CSS"
        + " pre-filter would improve evaluation precision by scoping the content to a specific"
        + " structural section.\n\n"
        + "Respond with ONLY a JSON object:\n"
        + "{\"needs_prefilter\": true/false, \"selector\": \"CSS selector or null\", \"reason\":"
        + " \"one sentence\"}\n\n"
        + "Rules:\n"
        + "- Only recommend a pre-filter when the intent references a specific structural"
        + " section (e.g. 'footer', 'sidebar', 'nav', 'header', 'main', 'article') OR the page"
        + " clearly has high-noise sections unrelated to the intent\n"
        + "- Use ONLY semantic element selectors: footer, nav, header, main, article, aside, or"
        + " attribute-based like [id*='price'], [class*='sidebar'] — NEVER positional selectors"
        + " like div:nth-child(3) or //*[2]\n"
        + "- Default to needs_prefilter=false — most intents don't need one\n"
        + "- selector must be null when needs_prefilter=false";
  }

  /** The default instructions, used when the operator has written none of their own. */
  public static final String DEFAULT_CHANGE_SUMMARY_PROMPT =
      "Describe what changed in plain English using these sections, in this fixed order — "
          + "omit a section entirely if there is nothing to report for it:\n"
          + "  Added: ...\n"
          + "  Changed: ...\n"
          + "  Removed: ...\n"
          + "The Removed section MUST always be last. Never place removals before additions or"
          + " changes.\n\n"
          + "List items as bullet points with key details for each one. Be considerate of the"
          + " style of content you are summarising and adjust your report accordingly.\n"
          + "Do not list standalone timestamps like '3 hours ago', 'Yesterday', '2 minutes ago'"
          + " as added or removed items — they are not meaningful content changes.\n"
          + "For content-heavy pages (news, listings, feeds): quote or paraphrase the specific"
          + " new headlines, items, or entries that were added — do not collapse them into vague"
          + " phrases like 'new articles were added' or 'section was expanded'.\n"
          + "For large blocks of new text (full articles, documents, long paragraphs): briefly"
          + " summarise the substance in 1-2 sentences capturing the key point — do not just"
          + " repeat the title.\n\n"
          + "Do not quote non-English text verbatim; translate and summarise all content into"
          + " English. Do not give partial listings such as 'Examples include:', always be"
          + " thorough.";
}
