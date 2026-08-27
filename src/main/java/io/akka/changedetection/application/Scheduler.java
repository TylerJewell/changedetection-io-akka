package io.akka.changedetection.application;

import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Which watches are due, and in what order.
 *
 * <p>Four separate things have to hold before a watch runs, and they are not interchangeable: it
 * must not be paused, the schedule must permit this moment, enough time must have passed since
 * it last ran, and a floor must have elapsed whatever its interval says. The floor exists
 * because a watch configured with no interval at all would otherwise be fetched as fast as the
 * machine can manage.
 *
 * <p>The most overdue watch goes first, so that a backlog drains oldest-first rather than
 * leaving one watch permanently behind.
 */
public final class Scheduler {

  /** The least time between two checks of the same watch, whatever it asked for. */
  public static final long MINIMUM_SECONDS_RECHECK_TIME = readMinimumRecheck();

  private static final Random RANDOM = new Random();

  private Scheduler() {}

  private static long readMinimumRecheck() {
    String value = System.getenv("MINIMUM_SECONDS_RECHECK_TIME");
    if (value == null || value.isBlank()) {
      return 3;
    }
    try {
      return Long.parseLong(value.strip());
    } catch (NumberFormatException e) {
      return 3;
    }
  }

  /**
   * The spread one watch's due moment carries, drawn once and then kept.
   *
   * <p>Uniform across the whole range, so about half of the watches configured with a spread
   * check a little early and half a little late. A draw that only ever ran late would look
   * identical on any one watch and would push every check past its interval.
   */
  public static double drawJitter(double jitterSeconds, Random random) {
    double bound = Math.abs(jitterSeconds);
    return random.nextDouble() * 2 * bound - bound;
  }

  /** One watch's answer, with why. */
  public record Decision(String uuid, boolean due, String reason, double jitter) {}

  public static List<Decision> decide(
      Store store, List<WatchesView.WatchRow> rows, ZonedDateTime now) {
    SettingsState settings = store.settings();
    Map<String, Object> application = settings.application();
    Map<String, Object> requests = settings.requests();

    List<Decision> decisions = new ArrayList<>();

    if (Fields.truthy(application.get("all_paused"))) {
      for (WatchesView.WatchRow row : rows) {
        decisions.add(new Decision(row.uuid(), false, "everything is paused", 0));
      }
      return decisions;
    }

    long systemInterval = Watch.thresholdSeconds(new Fields(requests).map("time_between_check"));
    double jitterSetting = new Fields(requests).number("jitter_seconds") == null
        ? 0 : new Fields(requests).number("jitter_seconds");
    String defaultTimezone = String.valueOf(application.getOrDefault("scheduler_timezone_default", ""));
    if (defaultTimezone.isBlank() || defaultTimezone.equals("null")) {
      String fromEnvironment = System.getenv("TZ");
      defaultTimezone = fromEnvironment == null || fromEnvironment.isBlank() ? "UTC" : fromEnvironment.strip();
    }

    // The most overdue first, so that a backlog drains from the oldest rather than starving one
    // watch while newer ones keep being picked.
    List<WatchesView.WatchRow> ordered = new ArrayList<>(rows);
    ordered.sort(Comparator.comparingLong(WatchesView.WatchRow::lastChecked));

    long nowSeconds = now.toEpochSecond();

    for (WatchesView.WatchRow row : ordered) {
      if (row.paused()) {
        decisions.add(new Decision(row.uuid(), false, "paused", 0));
        continue;
      }
      if (row.checking()) {
        decisions.add(new Decision(row.uuid(), false, "already running", 0));
        continue;
      }

      Map<String, Object> scheduleLimit;
      if (row.useDefaultInterval()) {
        Object global = requests.get("time_schedule_limit");
        scheduleLimit = global instanceof Map<?, ?> map ? castMap(map) : null;
      } else {
        WatchState state = store.watch(row.uuid());
        scheduleLimit = state.exists() ? state.asWatch().fields().map("time_schedule_limit") : null;
      }
      if (scheduleLimit != null && Fields.truthy(scheduleLimit.get("enabled"))) {
        try {
          if (!Schedule.isWithin(scheduleLimit, defaultTimezone, now)) {
            decisions.add(new Decision(row.uuid(), false, "outside its schedule", 0));
            continue;
          }
        } catch (RuntimeException e) {
          decisions.add(new Decision(row.uuid(), false, "schedule could not be read", 0));
          continue;
        }
      }

      long threshold = row.useDefaultInterval() ? systemInterval : row.intervalSeconds();

      double jitter = 0;
      if (jitterSetting > 0) {
        WatchState state = store.watch(row.uuid());
        jitter = state.jitterSeconds();
        if (jitter == 0) {
          jitter = drawJitter(jitterSetting, RANDOM);
        }
      }

      long elapsed = nowSeconds - row.lastChecked();
      boolean due = elapsed >= threshold + jitter && elapsed >= MINIMUM_SECONDS_RECHECK_TIME;
      decisions.add(
          new Decision(
              row.uuid(),
              due,
              due ? "due" : "not yet due (" + elapsed + "s of " + threshold + "s)",
              jitter));
    }

    return decisions;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
