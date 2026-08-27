package io.akka.changedetection.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.WatchDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One watched page. */
@Component(id = "watch")
public class WatchEntity extends EventSourcedEntity<WatchState, WatchEvent> {

  public record Create(Map<String, Object> fields, long at) {}

  public record Update(Map<String, Object> fields, long at) {}

  public record RecordCheck(Map<String, Object> fields, long at) {}

  public record RecordSnapshot(long timestamp, String checksum, int textLength) {}

  public record TrimHistory(int keepNewest) {}

  public record MarkViewed(long timestamp) {}

  public record SetPaused(boolean paused) {}

  public record SetMuted(boolean muted) {}

  public record DrawJitter(double seconds) {}

  public record NoteStatus(String status) {}

  @Override
  public WatchState emptyState() {
    return WatchState.empty();
  }

  public Effect<String> create(Create command) {
    if (currentState().exists()) {
      return effects().error("watch " + commandContext().entityId() + " already exists");
    }
    Map<String, Object> fields = WatchDefaults.create(commandContext().entityId());
    fields.putAll(command.fields());
    fields.put("uuid", commandContext().entityId());
    if (fields.get("date_created") == null) {
      fields.put("date_created", command.at());
    }
    return effects()
        .persist(new WatchEvent.Created(commandContext().entityId(), fields, command.at()))
        .thenReply(state -> state.uuid());
  }

  public ReadOnlyEffect<WatchState> read() {
    return effects().reply(currentState());
  }

  /**
   * A change the operator made.
   *
   * <p>Recorded separately from what a check writes, because this is what arms the rule that
   * the next check must reprocess the page even if it has not moved.
   */
  public Effect<String> update(Update command) {
    if (!currentState().exists()) {
      return effects().error("no such watch");
    }
    return effects()
        .persist(new WatchEvent.Edited(command.fields(), command.at()))
        .thenReply(state -> "ok");
  }

  /** What a check found out, which does not count as the operator having changed anything. */
  public Effect<String> recordCheck(RecordCheck command) {
    if (!currentState().exists()) {
      return effects().error("no such watch");
    }
    return effects()
        .persist(new WatchEvent.Checked(command.fields(), command.at()))
        .thenReply(state -> "ok");
  }

  public Effect<String> recordSnapshot(RecordSnapshot command) {
    if (!currentState().exists()) {
      return effects().error("no such watch");
    }
    return effects()
        .persist(
            new WatchEvent.SnapshotRecorded(
                command.timestamp(), command.checksum(), command.textLength()))
        .thenReply(state -> "ok");
  }

  /**
   * The oldest snapshots dropped.
   *
   * <p>The count is a limit on what is kept, not on what is compared: the rule that asks
   * whether every line has been seen before reads all of them, so trimming changes that
   * answer. It is applied only where the operator asked for a limit.
   */
  public Effect<List<Long>> trimHistory(TrimHistory command) {
    List<Long> history = new ArrayList<>(currentState().history());
    if (command.keepNewest() <= 0 || history.size() <= command.keepNewest()) {
      return effects().reply(List.of());
    }
    List<Long> dropped = new ArrayList<>(history.subList(0, history.size() - command.keepNewest()));
    List<Long> kept = new ArrayList<>(history.subList(history.size() - command.keepNewest(), history.size()));
    return effects().persist(new WatchEvent.HistoryTrimmed(kept)).thenReply(state -> dropped);
  }

  public Effect<List<Long>> clearHistory() {
    List<Long> dropped = new ArrayList<>(currentState().history());
    return effects()
        .persist(new WatchEvent.HistoryCleared(System.currentTimeMillis() / 1000))
        .thenReply(state -> dropped);
  }

  public Effect<String> markViewed(MarkViewed command) {
    if (!currentState().exists()) {
      return effects().error("no such watch");
    }
    return effects().persist(new WatchEvent.Viewed(command.timestamp())).thenReply(s -> "ok");
  }

  public Effect<String> setPaused(SetPaused command) {
    if (!currentState().exists()) {
      return effects().error("no such watch");
    }
    return effects().persist(new WatchEvent.PauseChanged(command.paused())).thenReply(s -> "ok");
  }

  public Effect<String> setMuted(SetMuted command) {
    if (!currentState().exists()) {
      return effects().error("no such watch");
    }
    return effects().persist(new WatchEvent.MuteChanged(command.muted())).thenReply(s -> "ok");
  }

  public Effect<String> drawJitter(DrawJitter command) {
    return effects().persist(new WatchEvent.JitterDrawn(command.seconds())).thenReply(s -> "ok");
  }

  public Effect<String> startCheck() {
    if (!currentState().exists()) {
      return effects().error("no such watch");
    }
    if (currentState().checking()) {
      return effects().reply("already-checking");
    }
    return effects()
        .persist(new WatchEvent.CheckStarted(System.currentTimeMillis() / 1000))
        .thenReply(s -> "ok");
  }

  public Effect<String> finishCheck() {
    return effects()
        .persist(new WatchEvent.CheckFinished(System.currentTimeMillis() / 1000))
        .thenReply(s -> "ok");
  }

  public Effect<String> noteStatus(NoteStatus command) {
    return effects().persist(new WatchEvent.StatusNoted(command.status())).thenReply(s -> "ok");
  }

  public Effect<String> delete() {
    if (!currentState().exists()) {
      return effects().reply("gone");
    }
    return effects()
        .persist(new WatchEvent.Deleted(System.currentTimeMillis() / 1000))
        .deleteEntity()
        .thenReply(s -> "ok");
  }

  @Override
  public WatchState applyEvent(WatchEvent event) {
    return switch (event) {
      case WatchEvent.Created e ->
          new WatchState(
              e.uuid(), Fields.deepCopy(e.fields()), List.of(), false, false, 0, false, null,
              e.at());

      case WatchEvent.Edited e -> currentState().withFields(e.fields(), true);

      case WatchEvent.Checked e -> currentState().withFields(e.fields(), false);

      case WatchEvent.SnapshotRecorded e -> {
        List<Long> history = new ArrayList<>(currentState().history());
        if (!history.contains(e.timestamp())) {
          history.add(e.timestamp());
        }
        yield currentState().withHistory(history);
      }

      case WatchEvent.HistoryTrimmed e -> currentState().withHistory(e.keptTimestamps());

      case WatchEvent.HistoryCleared e -> {
        Map<String, Object> cleared = new LinkedHashMap<>();
        cleared.put("last_viewed", 0);
        cleared.put("previous_md5", false);
        yield currentState().withHistory(List.of()).withFields(cleared, false);
      }

      case WatchEvent.Viewed e -> {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("last_viewed", e.timestamp());
        yield currentState().withFields(changes, false);
      }

      case WatchEvent.PauseChanged e -> {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("paused", e.paused());
        yield currentState().withFields(changes, true);
      }

      case WatchEvent.MuteChanged e -> {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("notification_muted", e.muted());
        yield currentState().withFields(changes, true);
      }

      case WatchEvent.JitterDrawn e -> currentState().withJitter(e.seconds());

      case WatchEvent.CheckStarted e -> {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("last_checked", e.at());
        yield currentState().withFields(changes, false).withChecking(true, "Fetching...");
      }

      case WatchEvent.CheckFinished e ->
          currentState().withChecking(false, null).withEdited(false).withJitter(0);

      case WatchEvent.StatusNoted e -> currentState().withChecking(true, e.status());

      case WatchEvent.Deleted e -> currentState().asDeleted();
    };
  }
}
