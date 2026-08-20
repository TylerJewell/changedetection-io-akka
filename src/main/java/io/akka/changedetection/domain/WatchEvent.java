package io.akka.changedetection.domain;

import akka.javasdk.annotations.TypeName;
import java.util.Optional;
import java.util.Set;

/** What happened to one watch. */
public sealed interface WatchEvent {

  @TypeName("configured")
  record Configured(WatchConfig config) implements WatchEvent {}

  /**
   * One check ran.
   *
   * <p>Carries the change to the watch's state rather than the state itself: the seen-line set
   * grows for as long as a watch runs, and an event repeating it would make the cost of one
   * check grow with the watch's age.
   *
   * @param snapshot what was seen. Empty unless the verdict was a change — nothing else adds to
   *     the history, so nothing else needs the text kept.
   */
  @TypeName("checked")
  record Checked(
      Verdict verdict,
      String snapshot,
      String rawChecksum,
      String ruleFingerprint,
      Optional<String> recordedChecksum,
      Set<String> newlySeenLines,
      long atEpochSeconds)
      implements WatchEvent {}
}
