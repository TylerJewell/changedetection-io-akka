package io.akka.changedetection.application;

import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.WatchDefaults;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * The window a watch is allowed to run in.
 *
 * <p>This is a separate question from whether enough time has passed. A watch checked every ten
 * minutes but scheduled only for weekday mornings does not run at midnight however overdue it
 * is -- and a watch that is inside its window but not yet due does not run either.
 *
 * <p>A window may run past midnight, so a moment can be inside a window that opened on the
 * previous day. That is why the day before and the day after are both tested rather than just
 * the current one: a window opening at eleven at night and lasting four hours covers two in the
 * morning of the following day, and testing only the current day would say no.
 */
public final class Schedule {

  private Schedule() {}

  /** True when the given moment falls inside the schedule the settings describe. */
  public static boolean isWithin(
      Map<String, Object> scheduleLimit, String defaultTimezone, ZonedDateTime now) {
    if (scheduleLimit == null || !Fields.truthy(scheduleLimit.get("enabled"))) {
      return false;
    }
    String zoneName = String.valueOf(scheduleLimit.getOrDefault("timezone", ""));
    if (zoneName.isBlank() || zoneName.equals("null")) {
      zoneName = defaultTimezone == null || defaultTimezone.isBlank() ? "UTC" : defaultTimezone;
    }
    ZoneId zone;
    try {
      zone = ZoneId.of(zoneName.strip());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid timezone '" + zoneName + "'");
    }

    ZonedDateTime moment = now.withZoneSameInstant(zone);
    String dayName = moment.getDayOfWeek().getDisplayName(
        java.time.format.TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);

    Object daySettings = scheduleLimit.get(dayName);
    if (!(daySettings instanceof Map<?, ?> raw)) {
      return false;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> day = (Map<String, Object>) raw;
    if (!Fields.truthy(day.get("enabled"))) {
      return false;
    }

    Object durationRaw = day.get("duration");
    int durationMinutes = 0;
    if (durationRaw instanceof Map<?, ?> durationMap) {
      durationMinutes =
          asInt(durationMap.get("hours")) * 60 + asInt(durationMap.get("minutes"));
    }

    String startTime = String.valueOf(day.getOrDefault("start_time", "00:00"));
    return isInsideWindow(moment.getDayOfWeek(), startTime, zone, durationMinutes, moment);
  }

  static boolean isInsideWindow(
      DayOfWeek targetDay,
      String startTime,
      ZoneId zone,
      int durationMinutes,
      ZonedDateTime now) {
    LocalTime start;
    try {
      String[] parts = startTime.split(":");
      int hour = Integer.parseInt(parts[0].strip());
      int minute = Integer.parseInt(parts[1].strip());
      if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
        throw new IllegalArgumentException("Invalid time '" + startTime + "'");
      }
      start = LocalTime.of(hour, minute);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid time '" + startTime + "'");
    }

    ZonedDateTime today =
        now.withHour(start.getHour()).withMinute(start.getMinute()).withSecond(0).withNano(0);
    DayOfWeek currentDay = now.getDayOfWeek();

    if (targetDay == currentDay.minus(1)) {
      ZonedDateTime started = today.minusDays(1);
      ZonedDateTime ended = started.plusMinutes(durationMinutes);
      if (!now.isBefore(started) && !now.isAfter(ended)) {
        return true;
      }
    }

    if (targetDay == currentDay) {
      ZonedDateTime ended = today.plusMinutes(durationMinutes);
      if (!now.isBefore(today) && !now.isAfter(ended)) {
        return true;
      }
    }

    if (targetDay == currentDay.plus(1)) {
      ZonedDateTime ended = today.plusMinutes(durationMinutes);
      if (now.isBefore(today) && !now.plusDays(1).isAfter(ended)) {
        return true;
      }
    }

    return false;
  }

  private static int asInt(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** The days a schedule holds, in the order it holds them. */
  public static java.util.List<String> days() {
    return WatchDefaults.DAYS;
  }
}
