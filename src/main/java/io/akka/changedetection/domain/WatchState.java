package io.akka.changedetection.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One watch, as it stands. SPEC-001 §2.
 *
 * @param history the snapshots of checks that reported a change, oldest first. An unchanged or
 *     blocked check adds nothing here, so two adjacent entries always differ.
 * @param lastCheckedEpochSeconds when a check last ran, whatever it concluded — a blocked check
 *     still ran, and the next due time is measured from it
 */
public record WatchState(
    WatchConfig config,
    DetectionState detection,
    List<String> history,
    Verdict lastVerdict,
    long lastCheckedEpochSeconds,
    long jitterSeconds) {

  /** Snapshots kept per watch. Beyond this the oldest is dropped. */
  public static final int HISTORY_LIMIT = 20;

  /**
   * Total characters of snapshot text kept per watch. A page of any size can be watched, and a
   * count alone would let twenty large pages carry the state past what the runtime replicates.
   */
  public static final int HISTORY_CHAR_BUDGET = 256_000;

  public static WatchState empty() {
    return new WatchState(null, DetectionState.empty(), List.of(), null, 0L, 0L);
  }

  public boolean isConfigured() {
    return config != null;
  }

  public WatchState configured(WatchConfig newConfig) {
    long jitter = Schedule.drawJitter(newConfig.jitterSeconds(), newConfig.url().hashCode());
    return new WatchState(newConfig, detection, history, lastVerdict, lastCheckedEpochSeconds, jitter);
  }

  public WatchState checked(
      Verdict verdict, DetectionState nextDetection, String snapshot, long atEpochSeconds) {
    List<String> nextHistory = verdict.isChange() ? trimmed(history, snapshot) : history;
    return new WatchState(
        config, nextDetection, nextHistory, verdict, atEpochSeconds, jitterSeconds);
  }

  /** The most recent snapshots that fit within both limits, oldest first. */
  private static List<String> trimmed(List<String> history, String addition) {
    Deque<String> kept = new ArrayDeque<>();
    kept.add(addition);
    int chars = addition.length();
    for (int i = history.size() - 1; i >= 0; i--) {
      String older = history.get(i);
      if (kept.size() >= HISTORY_LIMIT || chars + older.length() > HISTORY_CHAR_BUDGET) {
        break;
      }
      kept.addFirst(older);
      chars += older.length();
    }
    return List.copyOf(new ArrayList<>(kept));
  }
}
