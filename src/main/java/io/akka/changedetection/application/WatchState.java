package io.akka.changedetection.application;

import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.model.WatchDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is remembered about one watched page between checks.
 *
 * <p>The edited flag is state rather than a derived value, and it has to be: it records that
 * the configuration changed since the last completed check, which nothing in the configuration
 * itself says. Without it, a change to a filter would only take effect the next time the page
 * happened to move.
 */
public record WatchState(
    String uuid,
    Map<String, Object> fields,
    List<Long> history,
    boolean edited,
    boolean deleted,
    double jitterSeconds,
    boolean checking,
    String checkStatus,
    long createdAt) {

  public static WatchState empty() {
    return new WatchState(
        "", new LinkedHashMap<>(), List.of(), false, false, 0, false, null, 0);
  }

  public boolean exists() {
    return !uuid.isEmpty() && !deleted;
  }

  /** The state as the domain object the decision procedure works with. */
  public Watch asWatch() {
    Watch watch = new Watch(new Fields(Fields.deepCopy(fields)));
    watch.setHistory(new ArrayList<>(history));
    watch.setJitterSeconds(jitterSeconds);
    if (edited) {
      // The flag lives in the state rather than on the object, so it is re-asserted here by
      // touching a field the object counts as an edit.
      watch.update(Map.of("__edited", true));
      watch.fields().remove("__edited");
    }
    return watch;
  }

  public WatchState withFields(Map<String, Object> changes, boolean asEdit) {
    Map<String, Object> merged = Fields.deepCopy(fields);
    boolean nowEdited = edited;
    for (Map.Entry<String, Object> entry : changes.entrySet()) {
      merged.put(entry.getKey(), entry.getValue());
      if (asEdit
          && !entry.getKey().startsWith("_")
          && !WatchDefaults.SYSTEM_MANAGED.contains(entry.getKey())
          && !entry.getKey().equals("last_viewed")) {
        nowEdited = true;
      }
    }
    return new WatchState(
        uuid, merged, history, nowEdited, deleted, jitterSeconds, checking, checkStatus, createdAt);
  }

  public WatchState withHistory(List<Long> timestamps) {
    List<Long> sorted = new ArrayList<>(timestamps);
    java.util.Collections.sort(sorted);
    return new WatchState(
        uuid, fields, List.copyOf(sorted), edited, deleted, jitterSeconds, checking, checkStatus,
        createdAt);
  }

  public WatchState withEdited(boolean value) {
    return new WatchState(
        uuid, fields, history, value, deleted, jitterSeconds, checking, checkStatus, createdAt);
  }

  public WatchState withJitter(double seconds) {
    return new WatchState(
        uuid, fields, history, edited, deleted, seconds, checking, checkStatus, createdAt);
  }

  public WatchState withChecking(boolean value, String status) {
    return new WatchState(
        uuid, fields, history, edited, deleted, jitterSeconds, value, status, createdAt);
  }

  public WatchState asDeleted() {
    return new WatchState(
        uuid, fields, history, edited, true, jitterSeconds, false, null, createdAt);
  }
}
