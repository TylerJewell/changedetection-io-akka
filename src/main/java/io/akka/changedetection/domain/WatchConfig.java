package io.akka.changedetection.domain;

import java.util.List;

/**
 * Everything an operator sets about one watched page. SPEC-001 §2.
 *
 * <p>The rule lists carry a fingerprint (see {@link #ruleFingerprint()}) because the
 * unchanged-raw-body short-circuit in R8 must not fire across a rule change: the body would be
 * identical and the answer different.
 */
public record WatchConfig(
    String url,
    Interval interval,
    boolean paused,
    Window window,
    long jitterSeconds,
    List<String> ignoreText,
    List<String> triggerText,
    List<String> forbiddenText,
    boolean checkUniqueLines,
    boolean stripIgnoredLines,
    boolean ignoreWhitespace) {

  public WatchConfig {
    ignoreText = List.copyOf(ignoreText);
    triggerText = List.copyOf(triggerText);
    forbiddenText = List.copyOf(forbiddenText);
  }

  public static WatchConfig of(String url) {
    return new WatchConfig(
        url,
        Interval.ofSeconds(300),
        false,
        null,
        0,
        List.of(),
        List.of(),
        List.of(),
        false,
        false,
        false);
  }

  public WatchConfig withInterval(Interval v) {
    return new WatchConfig(url, v, paused, window, jitterSeconds, ignoreText, triggerText,
        forbiddenText, checkUniqueLines, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withPaused(boolean v) {
    return new WatchConfig(url, interval, v, window, jitterSeconds, ignoreText, triggerText,
        forbiddenText, checkUniqueLines, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withWindow(Window v) {
    return new WatchConfig(url, interval, paused, v, jitterSeconds, ignoreText, triggerText,
        forbiddenText, checkUniqueLines, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withJitterSeconds(long v) {
    return new WatchConfig(url, interval, paused, window, v, ignoreText, triggerText,
        forbiddenText, checkUniqueLines, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withIgnoreText(List<String> v) {
    return new WatchConfig(url, interval, paused, window, jitterSeconds, v, triggerText,
        forbiddenText, checkUniqueLines, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withTriggerText(List<String> v) {
    return new WatchConfig(url, interval, paused, window, jitterSeconds, ignoreText, v,
        forbiddenText, checkUniqueLines, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withForbiddenText(List<String> v) {
    return new WatchConfig(url, interval, paused, window, jitterSeconds, ignoreText, triggerText,
        v, checkUniqueLines, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withCheckUniqueLines(boolean v) {
    return new WatchConfig(url, interval, paused, window, jitterSeconds, ignoreText, triggerText,
        forbiddenText, v, stripIgnoredLines, ignoreWhitespace);
  }

  public WatchConfig withStripIgnoredLines(boolean v) {
    return new WatchConfig(url, interval, paused, window, jitterSeconds, ignoreText, triggerText,
        forbiddenText, checkUniqueLines, v, ignoreWhitespace);
  }

  public WatchConfig withIgnoreWhitespace(boolean v) {
    return new WatchConfig(url, interval, paused, window, jitterSeconds, ignoreText, triggerText,
        forbiddenText, checkUniqueLines, stripIgnoredLines, v);
  }

  /** SPEC-001 §3 R8: what the short-circuit compares besides the body itself. */
  public String ruleFingerprint() {
    // The delimiters are load-bearing: joined without them, one pattern "ab" and two patterns
    // "a","b" fingerprint the same, and a rule edit between them would not disarm R8.
    return TextPreparation.md5(
        String.join("\n", ignoreText.stream().sorted().toList())
            + "\n--\n"
            + String.join("\n", triggerText.stream().sorted().toList())
            + "\n--\n"
            + String.join("\n", forbiddenText.stream().sorted().toList())
            + "\n--\n"
            + checkUniqueLines
            + stripIgnoredLines
            + ignoreWhitespace);
  }
}
