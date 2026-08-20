package io.akka.changedetection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R12-R14: the two rules that block, and what a blocked check does not spend. */
class BlockingRulesTest {

  @Test
  void anAbsentTriggerBlocksAndSpendsNoChecksum() {
    var run = new Session(WatchConfig.of("u").withTriggerText(List.of("In stock")));
    assertThat(run.verdictOf("Out of stock")).isEqualTo(Verdict.BLOCKED_NO_TRIGGER);
    assertThat(run.state().previousChecksum()).isEmpty();
    assertThat(run.verdictOf("In stock")).isEqualTo(Verdict.CHANGED);
  }

  @Test
  void forbiddenTextBlocks() {
    var run = new Session(WatchConfig.of("u").withForbiddenText(List.of("Sold out")));
    assertThat(run.verdictOf("Available")).isEqualTo(Verdict.CHANGED);
    assertThat(run.verdictOf("Sold out")).isEqualTo(Verdict.BLOCKED_FORBIDDEN);
  }

  @Test
  void aBlockedCheckLeavesTheStoredChecksumWhereItWas() {
    var run = new Session(WatchConfig.of("u").withForbiddenText(List.of("Sold out")));
    run.check("Available");
    run.check("Sold out");
    assertThat(run.verdictOf("Available")).isEqualTo(Verdict.UNCHANGED);
  }

  @Test
  void anIgnoredLineCannotFireATrigger() {
    var run =
        new Session(
            WatchConfig.of("u")
                .withTriggerText(List.of("In stock"))
                .withIgnoreText(List.of("In stock")));
    assertThat(run.verdictOf("In stock")).isEqualTo(Verdict.BLOCKED_NO_TRIGGER);
  }

  @Test
  void anIgnoredLineCanStillBlockOnForbiddenText() {
    var run =
        new Session(
            WatchConfig.of("u")
                .withForbiddenText(List.of("Sold out"))
                .withIgnoreText(List.of("Sold out")));
    run.check("Price 1");
    assertThat(run.verdictOf("Price 2\nSold out")).isEqualTo(Verdict.BLOCKED_FORBIDDEN);
  }

  @Test
  void strippingIgnoredLinesAlsoStopsThemBlocking() {
    var run =
        new Session(
            WatchConfig.of("u")
                .withForbiddenText(List.of("Sold out"))
                .withIgnoreText(List.of("Sold out"))
                .withStripIgnoredLines(true));
    run.check("Price 1");
    assertThat(run.verdictOf("Price 2\nSold out")).isEqualTo(Verdict.CHANGED);
  }
}
