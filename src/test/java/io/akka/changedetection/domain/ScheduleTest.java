package io.akka.changedetection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R1–R5: when a watch is due. */
class ScheduleTest {

  @Test
  void intervalIsTheSumOfFiveNamedUnits() {
    assertThat(new Interval(1, 2, 3, 4, 5).toDuration())
        .isEqualTo(Duration.ofSeconds(7 * 86400 + 2 * 86400 + 3 * 3600 + 4 * 60 + 5));
  }

  @Test
  void unsetUnitsContributeNothing() {
    assertThat(new Interval(0, 0, 1, 30, 0).toDuration()).isEqualTo(Duration.ofSeconds(5400));
    assertThat(Interval.none().toDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  void notDueBeforeTheIntervalHasElapsed() {
    assertThat(Schedule.isDue(1000, 900, Duration.ofSeconds(300), 0, false)).isFalse();
  }

  @Test
  void dueAtExactlyTheInterval() {
    assertThat(Schedule.isDue(1200, 900, Duration.ofSeconds(300), 0, false)).isTrue();
  }

  @Test
  void theFloorBeatsAZeroInterval() {
    assertThat(Schedule.isDue(902, 900, Duration.ZERO, 0, false)).isFalse();
    assertThat(Schedule.isDue(903, 900, Duration.ZERO, 0, false)).isTrue();
  }

  @Test
  void aPausedWatchIsNeverDue() {
    assertThat(Schedule.isDue(99999, 900, Duration.ofSeconds(300), 0, true)).isFalse();
  }

  @Test
  void positiveJitterDelaysTheDueMoment() {
    assertThat(Schedule.isDue(1200, 900, Duration.ofSeconds(300), 30, false)).isFalse();
    assertThat(Schedule.isDue(1230, 900, Duration.ofSeconds(300), 30, false)).isTrue();
  }

  @Test
  void negativeJitterAdvancesItButCannotBeatTheFloor() {
    assertThat(Schedule.isDue(1170, 900, Duration.ofSeconds(300), -30, false)).isTrue();
    assertThat(Schedule.isDue(901, 900, Duration.ofSeconds(10), -30, false)).isFalse();
  }

  @Test
  void jitterIsDrawnOnceAndStaysWithinTheConfiguredSpread() {
    long first = Schedule.drawJitter(30, 12345L);
    assertThat(Schedule.drawJitter(30, 12345L)).isEqualTo(first);
    for (long seed = 0; seed < 200; seed++) {
      assertThat(Schedule.drawJitter(30, seed)).isBetween(-30L, 30L);
    }
    assertThat(Schedule.drawJitter(0, 999L)).isZero();
  }
}
