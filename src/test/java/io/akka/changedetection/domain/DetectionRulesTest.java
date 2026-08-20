package io.akka.changedetection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R8, R10, R15-R18: the verdict, and what it does to the stored checksum. */
class DetectionRulesTest {

  private static WatchConfig config() {
    return WatchConfig.of("https://example.test/");
  }

  @Test
  void anIdenticalRawBodyEndsTheCheckBeforeAnyRuleRuns() {
    var run = new Session(config());
    run.check("Price: 10");
    assertThat(run.verdictOf("Price: 10")).isEqualTo(Verdict.UNCHANGED_RAW_IDENTICAL);
  }

  @Test
  void aChangedRuleSetDisarmsTheShortCircuit() {
    var plain = new Session(config());
    var first = plain.check("Price: 10\nLast updated: 1");

    var edited = config().withIgnoreText(List.of("Last updated"));
    var second =
        DetectionRules.decide(
            edited, first.applyTo(DetectionState.empty()), "Price: 10\nLast updated: 1",
            ContentType.PLAIN);
    assertThat(second.verdict()).isNotEqualTo(Verdict.UNCHANGED_RAW_IDENTICAL);
  }

  @Test
  void theFirstCheckOfAWatchIsAChange() {
    assertThat(new Session(config()).verdictOf("Price: 10")).isEqualTo(Verdict.CHANGED);
  }

  @Test
  void aChangeConfinedToAnIgnoredLineIsNotAChange() {
    var run = new Session(config().withIgnoreText(List.of("Last updated")));
    assertThat(run.verdictOf("Price: 10\nLast updated: 1")).isEqualTo(Verdict.CHANGED);
    assertThat(run.verdictOf("Price: 10\nLast updated: 2")).isEqualTo(Verdict.UNCHANGED);
    assertThat(run.verdictOf("Price: 11\nLast updated: 2")).isEqualTo(Verdict.CHANGED);
  }

  @Test
  void theStoredSnapshotKeepsIgnoredLinesUnlessAsked() {
    var keeping = new Session(config().withIgnoreText(List.of("Noise")));
    assertThat(keeping.check("Real\nNoise").snapshot()).contains("Noise");

    var stripping =
        new Session(config().withIgnoreText(List.of("Noise")).withStripIgnoredLines(true));
    assertThat(stripping.check("Real\nNoise").snapshot()).doesNotContain("Noise");
  }

  @Test
  void ignoringWhitespaceChangesTheChecksumAndNotTheSnapshot() {
    var lenient = new Session(config().withIgnoreWhitespace(true));
    lenient.check("a b");
    var second = lenient.check("a    b");
    assertThat(second.verdict()).isEqualTo(Verdict.UNCHANGED);
    assertThat(second.snapshot()).isEqualTo("a    b");

    var strict = new Session(config().withIgnoreWhitespace(false));
    strict.check("a b");
    assertThat(strict.verdictOf("a    b")).isEqualTo(Verdict.CHANGED);
  }

  @Test
  void uniqueLinesSuppressesAReturnToAlreadySeenContent() {
    var run = new Session(config().withCheckUniqueLines(true));
    assertThat(run.verdictOf("A\nB")).isEqualTo(Verdict.CHANGED);
    assertThat(run.verdictOf("A\nB\nC")).isEqualTo(Verdict.CHANGED);
    assertThat(run.verdictOf("A\nB")).isEqualTo(Verdict.UNCHANGED_NO_UNIQUE_LINES);
    assertThat(run.verdictOf("A\nD")).isEqualTo(Verdict.CHANGED);
  }

  @Test
  void withoutTheRuleTheSameReturnIsAChange() {
    var run = new Session(config());
    run.check("A\nB");
    run.check("A\nB\nC");
    assertThat(run.verdictOf("A\nB")).isEqualTo(Verdict.CHANGED);
  }

  @Test
  void seenLinesAreComparedLowerCasedAndTrimmed() {
    var run = new Session(config().withCheckUniqueLines(true));
    run.check("A\nB");
    assertThat(run.state().seenLines()).isEqualTo(Set.of("a", "b"));
    assertThat(run.verdictOf("  a  \n  b  ")).isEqualTo(Verdict.UNCHANGED_NO_UNIQUE_LINES);
  }

  @Test
  void aBlockedCheckDoesNotAddItsLinesToTheSeenSet() {
    var run =
        new Session(config().withCheckUniqueLines(true).withForbiddenText(List.of("Sold out")));
    run.check("A");
    run.check("A\nSold out");
    assertThat(run.state().seenLines()).isEqualTo(Set.of("a"));
  }

  @Test
  void anUnreportedChangeDoesNotAddItsLinesToTheSeenSetEither() {
    var run = new Session(config().withCheckUniqueLines(true));
    run.check("A\nB");
    run.check("A\nB\nC");
    // Returning to A,B is suppressed; nothing new was recorded, so the seen set has not moved.
    run.check("A\nB");
    assertThat(run.state().seenLines()).isEqualTo(Set.of("a", "b", "c"));
  }
}
