package io.akka.changedetection.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.changedetection.domain.ContentType;
import io.akka.changedetection.domain.Interval;
import io.akka.changedetection.domain.Verdict;
import io.akka.changedetection.domain.WatchConfig;
import io.akka.changedetection.domain.WatchEvent;
import io.akka.changedetection.domain.WatchState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R15, R16, R18, R19: the verdict held as durable state. */
class WatchEntityTest {

  private EventSourcedTestKit<WatchState, WatchEvent, WatchEntity> watch(WatchConfig config) {
    var kit = EventSourcedTestKit.of("watch-1", WatchEntity::new);
    kit.method(WatchEntity::configure).invoke(config);
    return kit;
  }

  private Verdict submit(
      EventSourcedTestKit<WatchState, WatchEvent, WatchEntity> kit, String body, long at) {
    return kit.method(WatchEntity::submit)
        .invoke(new WatchEntity.Submission(body, ContentType.PLAIN, at))
        .getReply()
        .verdict();
  }

  @Test
  void configuringAWatchEmitsExactlyOneEvent() {
    var kit = EventSourcedTestKit.<WatchState, WatchEvent, WatchEntity>of("watch-1", WatchEntity::new);
    var result = kit.method(WatchEntity::configure).invoke(WatchConfig.of("https://example.test/"));
    assertThat(result.getAllEvents()).hasSize(1);
    assertThat(result.getNextEventOfType(WatchEvent.Configured.class).config().url())
        .isEqualTo("https://example.test/");
  }

  @Test
  void theFirstCheckIsAChangeAndCarriesNoNotification() {
    var kit = watch(WatchConfig.of("u"));
    var reply =
        kit.method(WatchEntity::submit)
            .invoke(new WatchEntity.Submission("Price: 10", ContentType.PLAIN, 1_000L))
            .getReply();
    assertThat(reply.verdict()).isEqualTo(Verdict.CHANGED);
    assertThat(reply.worthReporting()).isFalse();
  }

  @Test
  void theSecondChangeIsWorthTellingSomeoneAbout() {
    var kit = watch(WatchConfig.of("u"));
    submit(kit, "Price: 10", 1_000L);
    var reply =
        kit.method(WatchEntity::submit)
            .invoke(new WatchEntity.Submission("Price: 11", ContentType.PLAIN, 2_000L))
            .getReply();
    assertThat(reply.verdict()).isEqualTo(Verdict.CHANGED);
    assertThat(reply.worthReporting()).isTrue();
  }

  @Test
  void anUnchangedCheckEmitsNoSnapshotEvent() {
    var kit = watch(WatchConfig.of("u"));
    submit(kit, "Price: 10", 1_000L);
    var result =
        kit.method(WatchEntity::submit)
            .invoke(new WatchEntity.Submission("Price: 10", ContentType.PLAIN, 2_000L));
    assertThat(result.getReply().verdict()).isEqualTo(Verdict.UNCHANGED_RAW_IDENTICAL);
    assertThat(result.getAllEvents()).allMatch(e -> e instanceof WatchEvent.Checked);
    assertThat(kit.getState().history()).hasSize(1);
  }

  @Test
  void aBlockedCheckStillRecordsThatTheWatchRan() {
    var kit = watch(WatchConfig.of("u").withTriggerText(List.of("In stock")));
    assertThat(submit(kit, "Out of stock", 5_000L)).isEqualTo(Verdict.BLOCKED_NO_TRIGGER);
    assertThat(kit.getState().lastCheckedEpochSeconds()).isEqualTo(5_000L);
    assertThat(kit.getState().history()).isEmpty();
  }

  @Test
  void seenLinesAccumulateAcrossChecksRatherThanBeingRecomputed() {
    var kit = watch(WatchConfig.of("u").withCheckUniqueLines(true));
    submit(kit, "A\nB", 1_000L);
    submit(kit, "A\nB\nC", 2_000L);
    assertThat(kit.getState().detection().seenLines()).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void changingTheIntervalIsRecordedAndReadBack() {
    var kit = watch(WatchConfig.of("u"));
    kit.method(WatchEntity::configure)
        .invoke(WatchConfig.of("u").withInterval(new Interval(0, 0, 0, 5, 0)));
    assertThat(kit.getState().config().interval().toDuration().toMinutes()).isEqualTo(5);
  }

  @Test
  void theDiffBetweenTheTwoMostRecentSnapshotsIsAvailable() {
    var kit = watch(WatchConfig.of("u"));
    submit(kit, "A\nB\nC", 1_000L);
    submit(kit, "A\nX\nC", 2_000L);
    assertThat(kit.method(WatchEntity::latestDiff).invoke().getReply().lines().toList())
        .containsExactly("B", "X");
  }
}
