package io.akka.changedetection.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Random;

/** When a watch is due, and when a window forbids the check outright. SPEC-001 §3 R1-R7. */
public final class Schedule {

  /**
   * No check runs sooner than this after the previous one, whatever the interval says. Matches
   * the source's MINIMUM_SECONDS_RECHECK_TIME default.
   */
  public static final long FLOOR_SECONDS = 3;

  private Schedule() {}

  /** SPEC-001 §3 R2, R3, R4, R5. */
  public static boolean isDue(
      long nowEpochSeconds,
      long lastCheckedEpochSeconds,
      Duration interval,
      long jitterSeconds,
      boolean paused) {
    if (paused) {
      return false;
    }
    long elapsed = nowEpochSeconds - lastCheckedEpochSeconds;
    return elapsed >= interval.getSeconds() + jitterSeconds && elapsed >= FLOOR_SECONDS;
  }

  /**
   * The offset a watch keeps for its whole life. SPEC-001 §3 R5.
   *
   * <p>Seeded rather than drawn from a shared generator so that the same watch draws the same
   * offset every time it is asked — the value is a property of the watch, not of when it was
   * asked.
   */
  public static long drawJitter(long spreadSeconds, long seed) {
    if (spreadSeconds <= 0) {
      return 0;
    }
    return new Random(seed).nextLong(-spreadSeconds, spreadSeconds + 1);
  }

  /**
   * SPEC-001 §3 R6, R7.
   *
   * <p>Only the day the instant falls in, in the window's own timezone, is consulted. A window
   * whose length would carry it past midnight stops at midnight.
   */
  public static boolean allowsCheck(Window window, Instant now) {
    if (window == null || !window.enabled()) {
      return true;
    }
    ZonedDateTime local = now.atZone(ZoneId.of(window.timezone()));
    Window.Day day = window.days().get(local.getDayOfWeek());
    if (day == null || !day.enabled()) {
      return false;
    }
    ZonedDateTime start = local.toLocalDate().atTime(day.start()).atZone(local.getZone());
    ZonedDateTime end = start.plus(day.length());
    return !local.isBefore(start) && !local.isAfter(end);
  }
}
