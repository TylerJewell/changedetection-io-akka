package io.akka.changedetection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R20-R22: the difference between two snapshots. */
class DiffTest {

  private static final EnumSet<Diff.Kind> ALL = EnumSet.allOf(Diff.Kind.class);

  @Test
  void identicalSnapshotsProduceNothing() {
    assertThat(Diff.render("A\nB\nC", "A\nB\nC", ALL)).isEmpty();
  }

  @Test
  void anAddedLineIsClassifiedAsAdded() {
    var chunks = Diff.chunks("A\nC", "A\nB\nC");
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).kind()).isEqualTo(Diff.Kind.ADDED);
    assertThat(chunks.get(0).after()).containsExactly("B");
    assertThat(chunks.get(0).before()).isEmpty();
  }

  @Test
  void aRemovedLineIsClassifiedAsRemoved() {
    var chunks = Diff.chunks("A\nB\nC", "A\nC");
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).kind()).isEqualTo(Diff.Kind.REMOVED);
    assertThat(chunks.get(0).before()).containsExactly("B");
  }

  @Test
  void aReplacedRunCarriesBothSides() {
    var chunks = Diff.chunks("A\nold\nC", "A\nnew\nC");
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).kind()).isEqualTo(Diff.Kind.REPLACED);
    assertThat(chunks.get(0).before()).containsExactly("old");
    assertThat(chunks.get(0).after()).containsExactly("new");
  }

  @Test
  void unchangedRunsAreOmitted() {
    assertThat(Diff.render("A\nB\nC\nD", "A\nB\nX\nD", ALL).lines().toList())
        .containsExactly("C", "X");
  }

  @Test
  void trailingWhitespaceDoesNotMakeALineDiffer() {
    assertThat(Diff.chunks("A\nB   ", "A\nB")).isEmpty();
  }

  @Test
  void aCallerCanAskForOnlySomeCategories() {
    // One of each: B becomes X, and E is removed from the end.
    var before = "A\nB\nC\nD\nE";
    var after = "A\nX\nC\nD";

    assertThat(Diff.render(before, after, EnumSet.of(Diff.Kind.REPLACED)).lines().toList())
        .containsExactly("B", "X");
    assertThat(Diff.render(before, after, EnumSet.of(Diff.Kind.REMOVED)).lines().toList())
        .containsExactly("E");
    assertThat(Diff.render(before, after, EnumSet.of(Diff.Kind.ADDED))).isEmpty();
    assertThat(Diff.render(before, after, ALL).lines().toList())
        .containsExactly("B", "X", "E");
  }

  @Test
  void anEmptyResultMeansTheSnapshotsAgreeInTheCategoriesAsked() {
    assertThat(Diff.render("A\nB", "A", EnumSet.of(Diff.Kind.ADDED))).isEmpty();
    assertThat(Diff.render("A\nB", "A", EnumSet.of(Diff.Kind.REMOVED))).isNotEmpty();
  }

  @Test
  void bothSnapshotsEmptyIsNotADifference() {
    assertThat(Diff.chunks("", "")).isEmpty();
  }
}
