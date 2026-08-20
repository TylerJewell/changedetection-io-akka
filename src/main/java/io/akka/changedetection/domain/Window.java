package io.akka.changedetection.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

/**
 * A day-and-time window outside which no check runs. SPEC-001 §3 R6, R7.
 *
 * <p>A day with no entry is a day on which no check runs. An absent or disabled window places no
 * restriction at all, which is a different answer from an empty one — see {@link
 * Schedule#allowsCheck}.
 */
public record Window(boolean enabled, String timezone, Map<DayOfWeek, Day> days) {

  public record Day(boolean enabled, LocalTime start, Duration length) {}

  public static Window disabled() {
    return new Window(false, "UTC", Map.of());
  }
}
