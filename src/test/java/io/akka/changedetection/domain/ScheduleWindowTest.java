package io.akka.changedetection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R6–R7: the day-and-time window that can forbid a check outright. */
class ScheduleWindowTest {

  private static Window on(DayOfWeek day, String start, Duration length, String zone) {
    return new Window(
        true, zone, Map.of(day, new Window.Day(true, LocalTime.parse(start), length)));
  }

  private static Instant at(String iso) {
    return Instant.parse(iso);
  }

  @Test
  void insideTheWindow() {
    // 2024-01-01 is a Monday.
    assertThat(
            Schedule.allowsCheck(
                on(DayOfWeek.MONDAY, "23:00", Duration.ofHours(3), "UTC"),
                at("2024-01-01T23:30:00Z")))
        .isTrue();
  }

  @Test
  void aWindowStopsAtMidnightRatherThanRunningIntoTheNextDay() {
    assertThat(
            Schedule.allowsCheck(
                on(DayOfWeek.MONDAY, "23:00", Duration.ofHours(3), "UTC"),
                at("2024-01-02T00:30:00Z")))
        .isFalse();
    assertThat(
            Schedule.allowsCheck(
                on(DayOfWeek.TUESDAY, "00:00", Duration.ofHours(1), "UTC"),
                at("2024-01-02T00:30:00Z")))
        .isTrue();
  }

  @Test
  void bothBoundariesAreInclusive() {
    var window = on(DayOfWeek.MONDAY, "09:00", Duration.ofHours(1), "UTC");
    assertThat(Schedule.allowsCheck(window, at("2024-01-01T09:00:00Z"))).isTrue();
    assertThat(Schedule.allowsCheck(window, at("2024-01-01T10:00:00Z"))).isTrue();
    assertThat(Schedule.allowsCheck(window, at("2024-01-01T08:59:59Z"))).isFalse();
    assertThat(Schedule.allowsCheck(window, at("2024-01-01T10:00:01Z"))).isFalse();
  }

  @Test
  void minutesCountTowardsTheDuration() {
    assertThat(
            Schedule.allowsCheck(
                on(DayOfWeek.MONDAY, "09:30", Duration.ofMinutes(45), "UTC"),
                at("2024-01-01T10:00:00Z")))
        .isTrue();
  }

  @Test
  void aDayThatIsNotEnabledForbidsTheCheck() {
    assertThat(
            Schedule.allowsCheck(
                on(DayOfWeek.FRIDAY, "09:00", Duration.ofHours(4), "UTC"),
                at("2024-01-01T10:00:00Z")))
        .isFalse();
  }

  @Test
  void theDayIsTheDayInTheWindowsOwnTimezone() {
    // 23:30Z on Monday is already 08:30 on Tuesday in Tokyo.
    var instant = at("2024-01-01T23:30:00Z");
    assertThat(Schedule.allowsCheck(on(DayOfWeek.MONDAY, "23:00", Duration.ofHours(1), "UTC"), instant))
        .isTrue();
    assertThat(
            Schedule.allowsCheck(
                on(DayOfWeek.TUESDAY, "08:00", Duration.ofHours(1), "Asia/Tokyo"), instant))
        .isTrue();
    assertThat(
            Schedule.allowsCheck(
                on(DayOfWeek.MONDAY, "23:00", Duration.ofHours(1), "Asia/Tokyo"), instant))
        .isFalse();
  }

  @Test
  void anAbsentOrDisabledWindowIsUnrestrictedRatherThanClosed() {
    assertThat(Schedule.allowsCheck(null, at("2024-01-01T03:00:00Z"))).isTrue();
    assertThat(Schedule.allowsCheck(Window.disabled(), at("2024-01-01T03:00:00Z"))).isTrue();
  }
}
