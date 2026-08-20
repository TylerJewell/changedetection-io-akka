package io.akka.changedetection.domain;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * What a watch carries between checks. SPEC-001 §2.
 *
 * @param previousChecksum the last checksum a check was <em>allowed</em> to record; empty until
 *     the first unblocked check, because a blocked one does not spend it (R14)
 * @param lastRawChecksum checksum of the last raw body received, whatever the verdict was
 * @param lastRuleFingerprint the rule set in force when {@code lastRawChecksum} was taken, so a
 *     rule edit disarms the short-circuit (R8)
 * @param seenLines every line of every recorded snapshot, lower-cased and trimmed, folded in as
 *     snapshots arrive rather than recomputed per check (R18)
 */
public record DetectionState(
    Optional<String> previousChecksum,
    Optional<String> lastRawChecksum,
    Optional<String> lastRuleFingerprint,
    Set<String> seenLines) {

  /**
   * Distinct lines remembered for the unique-lines rule. Bounded because the set is durable state
   * and a watch on a busy page would otherwise grow it without limit; oldest lines are forgotten
   * first, which is the same direction the original's history trimming forgets in.
   */
  public static final int SEEN_LINES_LIMIT = 5_000;

  public DetectionState {
    seenLines = Set.copyOf(seenLines);
  }

  public static DetectionState empty() {
    return new DetectionState(Optional.empty(), Optional.empty(), Optional.empty(), Set.of());
  }

  /** This state with one check's recorded change folded in. */
  public DetectionState applying(
      Optional<String> recordedChecksum,
      String rawChecksum,
      String ruleFingerprint,
      Set<String> newlySeenLines) {
    return new DetectionState(
        recordedChecksum.or(() -> previousChecksum),
        Optional.of(rawChecksum),
        Optional.of(ruleFingerprint),
        plusSeen(newlySeenLines));
  }

  /** The lines of {@code snapshot} that have not been recorded before, in the order they appear. */
  public Set<String> unseenLinesOf(String snapshot) {
    var unseen = new LinkedHashSet<String>();
    snapshot
        .lines()
        .map(line -> line.strip().toLowerCase())
        .filter(line -> !seenLines.contains(line))
        .forEach(unseen::add);
    return unseen;
  }

  /** The seen set with {@code lines} folded in, oldest forgotten once past the limit. */
  public Set<String> plusSeen(Set<String> lines) {
    if (lines.isEmpty()) {
      return seenLines;
    }
    var next = new LinkedHashSet<>(seenLines);
    next.addAll(lines);
    Iterator<String> oldest = next.iterator();
    while (next.size() > SEEN_LINES_LIMIT && oldest.hasNext()) {
      oldest.next();
      oldest.remove();
    }
    return next;
  }
}
