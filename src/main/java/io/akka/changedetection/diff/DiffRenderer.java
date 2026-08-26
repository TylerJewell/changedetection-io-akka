package io.akka.changedetection.diff;

import io.akka.changedetection.text.PythonText;
import io.akka.changedetection.text.SequenceMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

/**
 * What changed between two snapshots, as text.
 *
 * <p>The result is not only shown to a person: it is also fed back into change detection. A
 * watch may be set to notice only additions, or only removals, and that is implemented by
 * running this and comparing the result -- so the placemarkers, the line ordering and which
 * lines count as "replaced" rather than "removed then added" all decide verdicts, not just
 * appearance.
 *
 * <p>Highlighting is written as placemarkers rather than as markup. A notification for a
 * service that cannot render markup has them stripped, one that can has them swapped for
 * styled spans, and one that speaks a lighter markup has them swapped for its own -- so the
 * decision about how a change is shown belongs to the delivery step and not to this one.
 */
public final class DiffRenderer {

  public static final String REMOVED_STYLE = "background-color: #fadad7; color: #b30000;";
  public static final String ADDED_STYLE = "background-color: #eaf2c2; color: #406619;";
  public static final String REMOVED_INNER_STYLE = "background-color: #ff867a; color: #111;";
  public static final String ADDED_INNER_STYLE = "background-color: #b2e841; color: #444;";
  public static final String CHANGED_STYLE = REMOVED_STYLE;
  public static final String CHANGED_INTO_STYLE = ADDED_STYLE;

  public static final String REMOVED_OPEN = "@removed_PLACEMARKER_OPEN";
  public static final String REMOVED_CLOSED = "@removed_PLACEMARKER_CLOSED";
  public static final String ADDED_OPEN = "@added_PLACEMARKER_OPEN";
  public static final String ADDED_CLOSED = "@added_PLACEMARKER_CLOSED";
  public static final String CHANGED_OPEN = "@changed_PLACEMARKER_OPEN";
  public static final String CHANGED_CLOSED = "@changed_PLACEMARKER_CLOSED";
  public static final String CHANGED_INTO_OPEN = "@changed_into_PLACEMARKER_OPEN";
  public static final String CHANGED_INTO_CLOSED = "@changed_into_PLACEMARKER_CLOSED";

  private static final Pattern WHITESPACE_NORMALIZE = Pattern.compile("\\s+");

  /** What the token encoder puts between tokens, and then removes again. */
  private static final String SEPARATOR = "\n";

  private static final Pattern EXTRACT_REMOVED =
      Pattern.compile(
          Pattern.quote(REMOVED_OPEN) + "(.*?)" + Pattern.quote(REMOVED_CLOSED)
              + "|"
              + Pattern.quote(CHANGED_OPEN) + "(.*?)" + Pattern.quote(CHANGED_CLOSED),
          Pattern.DOTALL);
  private static final Pattern EXTRACT_ADDED =
      Pattern.compile(
          Pattern.quote(ADDED_OPEN) + "(.*?)" + Pattern.quote(ADDED_CLOSED)
              + "|"
              + Pattern.quote(CHANGED_INTO_OPEN) + "(.*?)" + Pattern.quote(CHANGED_INTO_CLOSED),
          Pattern.DOTALL);

  /** Everything a caller may vary about how the difference is rendered. */
  public static final class Options {
    public boolean includeEqual = false;
    public boolean includeRemoved = true;
    public boolean includeAdded = true;
    public boolean includeReplaced = true;
    public boolean includeChangeTypePrefix = true;
    public boolean patchFormat = false;
    public boolean wordDiff = true;
    public int contextLines = 0;
    public boolean caseInsensitive = false;
    public boolean ignoreJunk = false;
    public String tokenizer = "words_and_html";

    public Options copy() {
      Options o = new Options();
      o.includeEqual = includeEqual;
      o.includeRemoved = includeRemoved;
      o.includeAdded = includeAdded;
      o.includeReplaced = includeReplaced;
      o.includeChangeTypePrefix = includeChangeTypePrefix;
      o.patchFormat = patchFormat;
      o.wordDiff = wordDiff;
      o.contextLines = contextLines;
      o.caseInsensitive = caseInsensitive;
      o.ignoreJunk = ignoreJunk;
      o.tokenizer = tokenizer;
      return o;
    }
  }

  private DiffRenderer() {}

  public static String render(String previous, String newest) {
    return render(previous, newest, new Options());
  }

  public static String render(String previous, String newest, Options options) {
    List<String> newestLines = rstripAll(newest);
    List<String> previousLines = rstripAll(previous);

    if (options.patchFormat) {
      return String.join("\n", SequenceMatcher.unifiedDiff(previousLines, newestLines));
    }

    List<List<String>> rendered = sequence(previousLines, newestLines, options);
    List<String> flat = new ArrayList<>();
    for (List<String> group : rendered) {
      flat.addAll(group);
    }
    return String.join("\n", flat);
  }

  private static List<String> rstripAll(String text) {
    List<String> out = new ArrayList<>();
    for (String line : PythonText.splitLines(text == null ? "" : text)) {
      out.add(PythonText.rstrip(line));
    }
    return out;
  }

  private static List<String> sameSlice(List<String> list, int start, int end) {
    return start != end ? list.subList(start, end) : List.of(list.get(start));
  }

  private static List<List<String>> sequence(
      List<String> before, List<String> after, Options options) {
    List<String> compareBefore = new ArrayList<>();
    List<String> compareAfter = new ArrayList<>();
    for (String line : before) {
      compareBefore.add(prepare(line, options));
    }
    for (String line : after) {
      compareAfter.add(prepare(line, options));
    }

    SequenceMatcher cruncher =
        new SequenceMatcher(SequenceMatcher.whitespaceLineIsJunk(), compareBefore, compareAfter);
    List<SequenceMatcher.OpCode> opcodes = cruncher.getOpCodes();

    Set<Integer> includedEqualRanges = new HashSet<>();
    if (options.contextLines > 0 && !options.includeEqual) {
      for (int i = 0; i < opcodes.size(); i++) {
        SequenceMatcher.OpCode code = opcodes.get(i);
        if (code.tag().equals("equal")) {
          continue;
        }
        for (int j = Math.max(0, i - 1); j < i; j++) {
          SequenceMatcher.OpCode previous = opcodes.get(j);
          if (previous.tag().equals("equal")) {
            int contextStart = Math.max(previous.i1(), previous.i2() - options.contextLines);
            for (int line = contextStart; line < previous.i2(); line++) {
              includedEqualRanges.add(line);
            }
          }
        }
        for (int j = i + 1; j < Math.min(opcodes.size(), i + 2); j++) {
          SequenceMatcher.OpCode next = opcodes.get(j);
          if (next.tag().equals("equal")) {
            int contextEnd = Math.min(next.i2(), next.i1() + options.contextLines);
            for (int line = next.i1(); line < contextEnd; line++) {
              includedEqualRanges.add(line);
            }
          }
        }
      }
    }

    List<List<String>> out = new ArrayList<>();
    for (SequenceMatcher.OpCode code : opcodes) {
      switch (code.tag()) {
        case "equal" -> {
          if (options.includeEqual) {
            out.add(new ArrayList<>(before.subList(code.i1(), code.i2())));
          } else if (options.contextLines > 0) {
            List<String> context = new ArrayList<>();
            for (int i = code.i1(); i < code.i2(); i++) {
              if (includedEqualRanges.contains(i)) {
                context.add(before.get(i));
              }
            }
            if (!context.isEmpty()) {
              out.add(context);
            }
          }
        }
        case "delete" -> {
          if (options.includeRemoved) {
            List<String> lines = new ArrayList<>();
            for (String line : sameSlice(before, code.i1(), code.i2())) {
              lines.add(
                  options.includeChangeTypePrefix ? REMOVED_OPEN + line + REMOVED_CLOSED : line);
            }
            out.add(lines);
          }
        }
        case "replace" -> {
          if (!options.includeReplaced) {
            break;
          }
          List<String> beforeLines = new ArrayList<>(sameSlice(before, code.i1(), code.i2()));
          List<String> afterLines = new ArrayList<>(sameSlice(after, code.j1(), code.j2()));
          if (options.wordDiff && beforeLines.size() == 1 && afterLines.size() == 1) {
            InlineDiff inline =
                renderInlineWordDiff(
                    beforeLines.get(0),
                    afterLines.get(0),
                    options.ignoreJunk,
                    options.tokenizer,
                    options.includeChangeTypePrefix);
            if (options.ignoreJunk && !inline.hasChanges()) {
              break;
            }
            out.add(List.of(inline.text()));
          } else {
            List<String> lines = new ArrayList<>();
            if (options.includeChangeTypePrefix) {
              for (String line : beforeLines) {
                lines.add(CHANGED_OPEN + line + CHANGED_CLOSED);
              }
              for (String line : afterLines) {
                lines.add(CHANGED_INTO_OPEN + line + CHANGED_INTO_CLOSED);
              }
            } else {
              lines.addAll(beforeLines);
              lines.addAll(afterLines);
            }
            out.add(lines);
          }
        }
        case "insert" -> {
          if (options.includeAdded) {
            List<String> lines = new ArrayList<>();
            for (String line : sameSlice(after, code.j1(), code.j2())) {
              lines.add(options.includeChangeTypePrefix ? ADDED_OPEN + line + ADDED_CLOSED : line);
            }
            out.add(lines);
          }
        }
        default -> {
          // No other opcode exists.
        }
      }
    }
    return out;
  }

  private static String prepare(String line, Options options) {
    String prepared = line;
    if (options.caseInsensitive) {
      prepared = prepared.toLowerCase(Locale.ROOT);
    }
    if (options.ignoreJunk) {
      prepared = WHITESPACE_NORMALIZE.matcher(prepared).replaceAll(" ");
    }
    return prepared;
  }

  /** The result of comparing two lines word by word. */
  public record InlineDiff(String text, boolean hasChanges) {}

  /** Two lines compared token by token, with the changed tokens marked in place. */
  public static InlineDiff renderInlineWordDiff(
      String beforeLine,
      String afterLine,
      boolean ignoreJunk,
      String tokenizer,
      boolean includeChangeTypePrefix) {
    String beforeNormalized =
        ignoreJunk ? WHITESPACE_NORMALIZE.matcher(beforeLine).replaceAll(" ") : beforeLine;
    String afterNormalized =
        ignoreJunk ? WHITESPACE_NORMALIZE.matcher(afterLine).replaceAll(" ") : afterLine;

    List<DiffMatchPatch.Diff> diffs =
        tokenDiffs(beforeNormalized, afterNormalized.isEmpty() ? " " : afterNormalized, tokenizer);

    boolean hasChanges = false;
    for (DiffMatchPatch.Diff diff : diffs) {
      if (diff.operation != DiffMatchPatch.Operation.EQUAL) {
        hasChanges = true;
        break;
      }
    }
    if (ignoreJunk && !hasChanges) {
      return new InlineDiff(afterLine, false);
    }

    boolean wholeLineReplaced = true;
    for (DiffMatchPatch.Diff diff : diffs) {
      if (diff.operation == DiffMatchPatch.Operation.EQUAL && !diff.text.strip().isEmpty()) {
        wholeLineReplaced = false;
        break;
      }
    }

    StringBuilder result = new StringBuilder();
    if (wholeLineReplaced) {
      StringBuilder removed = new StringBuilder();
      StringBuilder added = new StringBuilder();
      for (DiffMatchPatch.Diff diff : diffs) {
        switch (diff.operation) {
          case EQUAL -> {
            removed.append(diff.text);
            added.append(diff.text);
          }
          case DELETE -> removed.append(diff.text);
          case INSERT -> added.append(diff.text);
          default -> {
            // No other operation exists.
          }
        }
      }
      boolean wroteRemoved = false;
      if (removed.length() > 0) {
        result.append(wrapWithTrailing(removed.toString(), CHANGED_OPEN, CHANGED_CLOSED,
            includeChangeTypePrefix));
        wroteRemoved = true;
      }
      if (added.length() > 0) {
        if (wroteRemoved) {
          result.append('\n');
        }
        result.append(wrapWithTrailing(added.toString(), CHANGED_INTO_OPEN, CHANGED_INTO_CLOSED,
            includeChangeTypePrefix));
      }
      return new InlineDiff(result.toString(), hasChanges);
    }

    for (DiffMatchPatch.Diff diff : diffs) {
      switch (diff.operation) {
        case EQUAL -> result.append(diff.text);
        case INSERT -> appendMarked(result, diff.text, ADDED_OPEN, ADDED_CLOSED,
            includeChangeTypePrefix);
        case DELETE -> appendMarked(result, diff.text, REMOVED_OPEN, REMOVED_CLOSED,
            includeChangeTypePrefix);
        default -> {
          // No other operation exists.
        }
      }
    }
    return new InlineDiff(result.toString(), hasChanges);
  }

  /**
   * Two lines shown one above the other, each with its own changed parts marked inside it.
   * Used where a difference is shown rather than compared.
   */
  public static String[] renderNestedLineDiff(
      String beforeLine, String afterLine, boolean ignoreJunk, String tokenizer) {
    String beforeNormalized =
        ignoreJunk ? WHITESPACE_NORMALIZE.matcher(beforeLine).replaceAll(" ") : beforeLine;
    String afterNormalized =
        ignoreJunk ? WHITESPACE_NORMALIZE.matcher(afterLine).replaceAll(" ") : afterLine;

    List<DiffMatchPatch.Diff> diffs =
        tokenDiffs(beforeNormalized, afterNormalized.isEmpty() ? " " : afterNormalized, tokenizer);

    boolean hasChanges = false;
    for (DiffMatchPatch.Diff diff : diffs) {
      if (diff.operation != DiffMatchPatch.Operation.EQUAL) {
        hasChanges = true;
        break;
      }
    }
    if (ignoreJunk && !hasChanges) {
      return new String[] {beforeLine, afterLine, "false"};
    }

    StringBuilder beforeContent = new StringBuilder();
    StringBuilder afterContent = new StringBuilder();
    for (DiffMatchPatch.Diff diff : diffs) {
      switch (diff.operation) {
        case EQUAL -> {
          beforeContent.append(diff.text);
          afterContent.append(diff.text);
        }
        case DELETE ->
            beforeContent
                .append("<span style=\"")
                .append(REMOVED_INNER_STYLE)
                .append("\">")
                .append(diff.text)
                .append("</span>");
        case INSERT ->
            afterContent
                .append("<span style=\"")
                .append(ADDED_INNER_STYLE)
                .append("\">")
                .append(diff.text)
                .append("</span>");
        default -> {
          // No other operation exists.
        }
      }
    }
    return new String[] {
      CHANGED_OPEN + beforeContent + CHANGED_CLOSED,
      CHANGED_INTO_OPEN + afterContent + CHANGED_INTO_CLOSED,
      String.valueOf(hasChanges)
    };
  }

  /**
   * The two lines compared with tokens as the unit rather than characters.
   *
   * <p>Character-level comparison of "63" and "66" reports one digit changed, which reads as a
   * price that changed by three rather than a price that changed. Encoding each token as a
   * single character before comparing keeps the token as the unit, and the semantic tidy-up
   * the library would otherwise apply is deliberately not run, because it re-splits tokens.
   */
  private static List<DiffMatchPatch.Diff> tokenDiffs(
      String before, String after, String tokenizer) {
    String beforeText = String.join(SEPARATOR, Tokenizers.byName(tokenizer, before));
    String afterText = String.join(SEPARATOR, Tokenizers.byName(tokenizer, after));

    Map<String, Character> codes = new HashMap<>();
    List<String> tokenByCode = new ArrayList<>();
    tokenByCode.add("");
    String encodedBefore = encode(beforeText, codes, tokenByCode);
    String encodedAfter = encode(afterText, codes, tokenByCode);

    DiffMatchPatch dmp = new DiffMatchPatch();
    LinkedList<DiffMatchPatch.Diff> diffs = dmp.diffMain(encodedBefore, encodedAfter, false);

    List<DiffMatchPatch.Diff> out = new ArrayList<>();
    for (DiffMatchPatch.Diff diff : diffs) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < diff.text.length(); i++) {
        text.append(tokenByCode.get(diff.text.charAt(i)));
      }
      // The original joins tokens with newlines to encode them and strips every newline back
      // out afterwards, so a newline inside a token does not survive either. Kept because the
      // two sides have to agree on the string, not because a line ever reaches here with one.
      out.add(new DiffMatchPatch.Diff(diff.operation, stripNewlines(text.toString())));
    }
    return out;
  }

  /**
   * Each distinct token given a character of its own, so the comparison runs over tokens.
   *
   * <p>Comparing the two lines directly would compare characters, and a price moving from 63 to
   * 66 would be reported as one digit changing rather than as the number changing.
   *
   * <p>The unit is the token <em>with the separator that follows it</em>, which is what the
   * original's encoder produces and is not a detail: the last token on a line has no separator,
   * so it is a different unit from the same word appearing earlier. Two lines that differ only
   * by trailing whitespace therefore share no unit at all and are reported as wholly replaced,
   * which is what the original reports.
   */
  private static String encode(
      String text, Map<String, Character> codes, List<String> tokenByCode) {
    StringBuilder encoded = new StringBuilder();
    int start = 0;
    while (start < text.length()) {
      int end = text.indexOf(SEPARATOR, start);
      String unit = end < 0 ? text.substring(start) : text.substring(start, end + 1);
      start = end < 0 ? text.length() : end + 1;
      Character code = codes.get(unit);
      if (code == null) {
        code = (char) tokenByCode.size();
        tokenByCode.add(unit);
        codes.put(unit, code);
      }
      encoded.append((char) code);
    }
    return encoded.toString();
  }

  private static String stripNewlines(String text) {
    return text.replace("\n", "");
  }

  private static String wrapWithTrailing(
      String text, String open, String close, boolean includeChangeTypePrefix) {
    String content = PythonText.rstrip(text);
    String trailing = text.length() > content.length() ? text.substring(content.length()) : "";
    return includeChangeTypePrefix ? open + content + close + trailing : content + trailing;
  }

  private static void appendMarked(
      StringBuilder result, String text, String open, String close,
      boolean includeChangeTypePrefix) {
    if (!includeChangeTypePrefix) {
      result.append(text);
      return;
    }
    String content = PythonText.rstrip(text);
    String trailing = text.length() > content.length() ? text.substring(content.length()) : "";
    if (!content.isEmpty()) {
      result.append(open).append(content).append(close).append(trailing);
    } else {
      result.append(trailing);
    }
  }

  /** Only the removed or changed-from fragments, for the token that shows the old value. */
  public static String extractChangedFrom(String rawDiff) {
    return extract(EXTRACT_REMOVED, rawDiff);
  }

  /** Only the added or changed-into fragments, for the token that shows the new value. */
  public static String extractChangedTo(String rawDiff) {
    return extract(EXTRACT_ADDED, rawDiff);
  }

  private static String extract(Pattern pattern, String rawDiff) {
    List<String> parts = new ArrayList<>();
    Matcher m = pattern.matcher(rawDiff == null ? "" : rawDiff);
    while (m.find()) {
      String value = m.group(1) != null ? m.group(1) : m.group(2);
      parts.add(value == null ? "" : value);
    }
    return String.join("\n", parts);
  }
}
