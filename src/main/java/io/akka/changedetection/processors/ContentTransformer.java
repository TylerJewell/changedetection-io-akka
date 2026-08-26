package io.akka.changedetection.processors;

import io.akka.changedetection.text.PyRegex;
import io.akka.changedetection.text.PythonText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The optional reshaping a watch may ask for before its text is compared. */
public final class ContentTransformer {

  private ContentTransformer() {}

  /**
   * Leading and trailing whitespace removed from every line.
   *
   * <p>A blank line between two blocks is collapsed away first, which is not what "trim" would
   * suggest and is part of the behaviour: a page that gains or loses a blank line does not
   * count as changed for a watch with this on.
   */
  public static String trimWhitespace(String text) {
    List<String> out = new ArrayList<>();
    for (String line : PythonText.splitLines(text.replace("\n\n", "\n"))) {
      out.add(PythonText.strip(line));
    }
    return String.join("\n", out);
  }

  /** Repeated lines removed, the first of each kept in place. */
  public static String removeDuplicateLines(String text) {
    LinkedHashSet<String> seen = new LinkedHashSet<>(PythonText.splitLines(text.replace("\n\n", "\n")));
    return String.join("\n", seen);
  }

  /** Lines sorted without regard to case, so a reordered page does not count as changed. */
  public static String sortAlphabetically(String text) {
    List<String> lines = PythonText.splitLines(text.replace("\n\n", "\n"));
    lines.sort(java.util.Comparator.comparing(line -> line.toLowerCase(Locale.ROOT)));
    return String.join("\n", lines);
  }

  /** Only the lines containing one of the given pieces of text, matched without case. */
  public static String extractLinesContaining(String text, List<String> substrings) {
    List<String> needles = new ArrayList<>();
    for (String substring : substrings) {
      if (!substring.strip().isEmpty()) {
        needles.add(substring.toLowerCase(Locale.ROOT));
      }
    }
    if (needles.isEmpty()) {
      return text;
    }
    List<String> kept = new ArrayList<>();
    for (String line : PythonText.splitLines(text)) {
      String lowered = line.toLowerCase(Locale.ROOT);
      for (String needle : needles) {
        if (lowered.contains(needle)) {
          kept.add(line);
          break;
        }
      }
    }
    return String.join("\n", kept);
  }

  /**
   * Only the parts of the text a set of patterns matches, each on its own line.
   *
   * <p>A pattern with groups contributes each group separately, which is how a page's price and
   * currency come out as two lines rather than one. A plain string is matched literally and
   * without case.
   */
  public static String extractByRegex(String text, List<String> patterns) {
    List<String> out = new ArrayList<>();
    for (String pattern : patterns) {
      if (PyRegex.isPerlStyle(pattern)) {
        Pattern compiled = PyRegex.compile(PyRegex.perlStyleToOptions(pattern));
        Matcher matcher = compiled.matcher(text);
        while (matcher.find()) {
          if (matcher.groupCount() > 1) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
              out.add(matcher.group(group) == null ? "" : matcher.group(group));
            }
            out.add("\n");
          } else {
            String value = matcher.groupCount() == 1
                ? (matcher.group(1) == null ? "" : matcher.group(1))
                : matcher.group();
            out.add(value);
            out.add("\n");
          }
        }
      } else {
        Pattern compiled =
            Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        Matcher matcher = compiled.matcher(text);
        while (matcher.find()) {
          out.add(matcher.group());
          out.add("\n");
        }
      }
    }
    return out.isEmpty() ? "" : String.join("", out);
  }
}
