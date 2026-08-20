package io.akka.changedetection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Timing benchmark mirroring `bench/bench_source.py` — the same three shapes, the same body
 * sizes, the same number of iterations. Numbers go in `bench/REPORT.md`.
 *
 * <p>It asserts only that the work happened; the figures are printed. A benchmark that fails on
 * a slow machine tells you about the machine.
 */
class BenchTimingTest {

  private static final int WARMUP = 2_000;
  private static final int ITERATIONS = 20_000;

  private static String plainBody(int lines) {
    var out = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      out.append("Item ").append(i).append(" costs ").append(i * 3).append(" today\n");
    }
    return out.toString();
  }

  private static String htmlBody(int lines) {
    var out = new StringBuilder("<html><body>");
    for (int i = 0; i < lines; i++) {
      out.append("<p>Item ").append(i).append(" costs <b>").append(i * 3).append("</b></p>");
    }
    return out.append("</body></html>").toString();
  }

  private static double microsPerCheck(WatchConfig config, String body, ContentType type) {
    var state = DetectionState.empty();
    for (int i = 0; i < WARMUP; i++) {
      DetectionRules.decide(config, state, body + i, type);
    }
    long start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      DetectionRules.decide(config, state, body + i, type);
    }
    return (System.nanoTime() - start) / 1_000.0 / ITERATIONS;
  }

  @Test
  void aPlainBodyWithNoRules() {
    double micros = microsPerCheck(WatchConfig.of("u"), plainBody(200), ContentType.PLAIN);
    System.out.printf("port  plain 200 lines, no rules       %8.1f us/check%n", micros);
    assertThat(micros).isGreaterThan(0);
  }

  @Test
  void aPlainBodyWithIgnoreRules() {
    var config = WatchConfig.of("u").withIgnoreText(List.of("costs 3 ", "costs 9 ", "nothing"));
    double micros = microsPerCheck(config, plainBody(200), ContentType.PLAIN);
    System.out.printf("port  plain 200 lines, 3 ignore rules %8.1f us/check%n", micros);
    assertThat(micros).isGreaterThan(0);
  }

  @Test
  void anHtmlBody() {
    double micros = microsPerCheck(WatchConfig.of("u"), htmlBody(200), ContentType.HTML);
    System.out.printf("port  html  200 lines, no rules       %8.1f us/check%n", micros);
    assertThat(micros).isGreaterThan(0);
  }

  @Test
  void theUniqueLinesRuleOverAFullSeenSet() {
    var config = WatchConfig.of("u").withCheckUniqueLines(true);
    var state = DetectionState.empty();
    // Fill the seen set to its bound, so the timing is of the rule at its worst, not its best.
    var filler = new StringBuilder();
    for (int i = 0; i < DetectionState.SEEN_LINES_LIMIT; i++) {
      filler.append("seeded line ").append(i).append('\n');
    }
    var outcome = DetectionRules.decide(config, state, filler.toString(), ContentType.PLAIN);
    var full = outcome.applyTo(state);
    assertThat(full.seenLines()).hasSize(DetectionState.SEEN_LINES_LIMIT);

    String body = plainBody(200);
    for (int i = 0; i < WARMUP; i++) {
      DetectionRules.decide(config, full, body + i, ContentType.PLAIN);
    }
    long start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      DetectionRules.decide(config, full, body + i, ContentType.PLAIN);
    }
    double micros = (System.nanoTime() - start) / 1_000.0 / ITERATIONS;
    System.out.printf("port  plain 200 lines, unique lines   %8.1f us/check%n", micros);
    assertThat(micros).isGreaterThan(0);
  }
}
