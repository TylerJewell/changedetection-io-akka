package io.akka.changedetection.application;

import akka.javasdk.annotations.TypeName;
import java.util.List;
import java.util.Map;

/** Everything that can happen to one watched page. */
public sealed interface WatchEvent {

  @TypeName("watch-created")
  record Created(String uuid, Map<String, Object> fields, long at) implements WatchEvent {}

  /**
   * Fields the operator changed.
   *
   * <p>Separate from the fields a check writes, because only one of the two arms the rule that
   * forces the next check to reprocess a page that has not moved.
   */
  @TypeName("watch-edited")
  record Edited(Map<String, Object> fields, long at) implements WatchEvent {}

  /** Fields a check wrote about itself: when it ran, what it found, what went wrong. */
  @TypeName("watch-checked")
  record Checked(Map<String, Object> fields, long at) implements WatchEvent {}

  @TypeName("watch-snapshot-recorded")
  record SnapshotRecorded(long timestamp, String checksum, int textLength) implements WatchEvent {}

  @TypeName("watch-history-trimmed")
  record HistoryTrimmed(List<Long> keptTimestamps) implements WatchEvent {}

  @TypeName("watch-history-cleared")
  record HistoryCleared(long at) implements WatchEvent {}

  @TypeName("watch-viewed")
  record Viewed(long timestamp) implements WatchEvent {}

  @TypeName("watch-paused")
  record PauseChanged(boolean paused) implements WatchEvent {}

  @TypeName("watch-muted")
  record MuteChanged(boolean muted) implements WatchEvent {}

  @TypeName("watch-deleted")
  record Deleted(long at) implements WatchEvent {}

  /** The spread this watch's next due moment carries, drawn once and then spent. */
  @TypeName("watch-jitter-drawn")
  record JitterDrawn(double seconds) implements WatchEvent {}

  @TypeName("watch-check-started")
  record CheckStarted(long at) implements WatchEvent {}

  @TypeName("watch-check-finished")
  record CheckFinished(long at) implements WatchEvent {}

  @TypeName("watch-status-noted")
  record StatusNoted(String status) implements WatchEvent {}
}
