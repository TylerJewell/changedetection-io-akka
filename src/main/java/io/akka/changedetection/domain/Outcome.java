package io.akka.changedetection.domain;

import java.util.Optional;
import java.util.Set;

/**
 * What one check concluded, and what a durable record of it has to carry.
 *
 * <p>The fields are the change to the watch's state, not the state itself. A record of the whole
 * state on every check would put the entire seen-line set into every entry of the journal, so the
 * cost of one check would grow with how long the watch has been running.
 *
 * @param snapshot the text that would be stored for this check. Present whatever the verdict, so
 *     a caller can show what was seen even when nothing was reported.
 * @param recordedChecksum the checksum this check was allowed to record, or empty if it was
 *     blocked (R14)
 * @param newlySeenLines the lines this check added to the seen set — empty unless it was a change
 */
public record Outcome(
    Verdict verdict,
    String snapshot,
    String rawChecksum,
    String ruleFingerprint,
    Optional<String> recordedChecksum,
    Set<String> newlySeenLines) {

  /** The state a following check starts from. */
  public DetectionState applyTo(DetectionState state) {
    return state.applying(recordedChecksum, rawChecksum, ruleFingerprint, newlySeenLines);
  }
}
