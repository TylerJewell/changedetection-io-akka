package io.akka.changedetection.domain;

import java.time.Duration;

/**
 * How long between checks, in the five named units the source uses. SPEC-001 §3 R1.
 *
 * <p>Kept as five numbers rather than one duration because that is what an operator sets and
 * reads back; the sum is derived.
 */
public record Interval(int weeks, int days, int hours, int minutes, int seconds) {

  public static Interval none() {
    return new Interval(0, 0, 0, 0, 0);
  }

  public static Interval ofSeconds(int seconds) {
    return new Interval(0, 0, 0, 0, seconds);
  }

  public Duration toDuration() {
    return Duration.ofSeconds(
        (long) weeks * 604800L
            + (long) days * 86400L
            + (long) hours * 3600L
            + (long) minutes * 60L
            + seconds);
  }
}
