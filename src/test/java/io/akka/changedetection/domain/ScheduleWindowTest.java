package io.akka.changedetection.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.changedetection.application.Schedule;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The day-and-time window a watch may be restricted to.
 *
 * <p>Two things about it are easy to get backwards and both are checked here: a window that is
 * absent or switched off does not close the watch, it stops applying at all; and a window whose
 * length would carry it past midnight stops at midnight rather than continuing into the next
 * day, which is a different answer from the one an interval-arithmetic implementation gives.
 */
class ScheduleWindowTest {

  private static Map<String, Object> window(
      String day, String startTime, int durationHours, int durationMinutes, String timezone) {
    Map<String, Object> duration = new LinkedHashMap<>();
    duration.put("hours", String.valueOf(durationHours));
    duration.put("minutes", String.valueOf(durationMinutes));

    Map<String, Object> daySettings = new LinkedHashMap<>();
    daySettings.put("enabled", true);
    daySettings.put("start_time", startTime);
    daySettings.put("duration", duration);

    Map<String, Object> limit = new LinkedHashMap<>();
    limit.put("enabled", true);
    limit.put("timezone", timezone);
    for (String name : Schedule.days()) {
      Map<String, Object> other = new LinkedHashMap<>();
      other.put("enabled", false);
      limit.put(name, other);
    }
    limit.put(day, daySettings);
    return limit;
  }

  private static ZonedDateTime at(String isoLocal, String zone) {
    return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), ZoneId.of(zone));
  }

  @Test
  void aMomentInsideTheWindowIsAllowed() {
    // 2026-08-26 is a Wednesday.
    Map<String, Object> limit = window("wednesday", "09:00", 8, 0, "UTC");
    assertTrue(Schedule.isWithin(limit, "UTC", at("2026-08-26T12:00", "UTC")));
  }

  @Test
  void bothBoundariesAreInside() {
    Map<String, Object> limit = window("wednesday", "09:00", 8, 0, "UTC");
    assertTrue(Schedule.isWithin(limit, "UTC", at("2026-08-26T09:00", "UTC")));
    assertTrue(Schedule.isWithin(limit, "UTC", at("2026-08-26T17:00", "UTC")));
  }

  @Test
  void aMomentOutsideTheWindowIsRefused() {
    Map<String, Object> limit = window("wednesday", "09:00", 8, 0, "UTC");
    assertFalse(Schedule.isWithin(limit, "UTC", at("2026-08-26T08:59", "UTC")));
    assertFalse(Schedule.isWithin(limit, "UTC", at("2026-08-26T17:01", "UTC")));
  }

  @Test
  void aWindowThatWouldRunPastMidnightStopsAtMidnight() {
    Map<String, Object> limit = window("wednesday", "22:00", 6, 0, "UTC");
    assertTrue(Schedule.isWithin(limit, "UTC", at("2026-08-26T23:59", "UTC")));
    // Four hours of the window fall on Thursday, and Thursday is switched off: it does not
    // carry over, so the small hours are outside.
    assertFalse(Schedule.isWithin(limit, "UTC", at("2026-08-27T01:00", "UTC")));
  }

  @Test
  void theDayIsTheOneTheWindowsOwnTimezoneIsIn() {
    Map<String, Object> limit = window("wednesday", "09:00", 8, 0, "Australia/Sydney");
    // 23:30 UTC on Tuesday is 09:30 Wednesday in Sydney, which is inside.
    assertTrue(Schedule.isWithin(limit, "UTC", at("2026-08-25T23:30", "UTC")));
  }

  @Test
  void aWindowThatIsSwitchedOffDoesNotApply() {
    Map<String, Object> limit = window("wednesday", "09:00", 8, 0, "UTC");
    limit.put("enabled", false);
    assertFalse(Schedule.isWithin(limit, "UTC", at("2026-08-26T12:00", "UTC")));
  }

  @Test
  void aWindowThatIsAbsentDoesNotApply() {
    assertFalse(Schedule.isWithin(null, "UTC", at("2026-08-26T12:00", "UTC")));
  }

  @Test
  void aDayWithNoSettingsIsOutside() {
    Map<String, Object> limit = window("wednesday", "09:00", 8, 0, "UTC");
    limit.remove("thursday");
    assertFalse(Schedule.isWithin(limit, "UTC", at("2026-08-27T12:00", "UTC")));
  }

  @Test
  void aTimezoneNobodyRecognisesIsRefusedRatherThanGuessed() {
    Map<String, Object> limit = window("wednesday", "09:00", 8, 0, "Mars/Olympus");
    assertThrows(
        IllegalArgumentException.class,
        () -> Schedule.isWithin(limit, "UTC", at("2026-08-26T12:00", "UTC")));
  }

  @Test
  void everyDayOfTheWeekIsNameable() {
    List<String> days = Schedule.days();
    for (String day : days) {
      Map<String, Object> limit = window(day, "00:00", 24, 0, "UTC");
      boolean anyInside = false;
      for (int offset = 0; offset < 7; offset++) {
        if (Schedule.isWithin(
            limit, "UTC", at("2026-08-24T12:00", "UTC").plusDays(offset))) {
          anyInside = true;
        }
      }
      assertTrue(anyInside, day + " never matched any day of a whole week");
    }
  }
}
