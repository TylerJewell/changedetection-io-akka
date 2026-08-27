package io.akka.changedetection.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.WatchDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The settings that apply to everything, and the tags.
 *
 * <p>Tags live here rather than each in their own entity because a tag is read on every check
 * of every watch it applies to -- it supplies filter and notification overrides -- and because
 * one tag may apply to a watch by matching its address rather than by being attached to it, so
 * deciding which tags a watch has means reading all of them.
 */
@Component(id = "settings")
public class SettingsEntity extends KeyValueEntity<SettingsState> {

  /** The one instance; there is nothing to key settings by. */
  public static final String ID = "settings";

  public record Replace(Map<String, Object> settings) {}

  public record UpdateApplication(Map<String, Object> fields) {}

  public record UpdateRequests(Map<String, Object> fields) {}

  public record UpdateHeaders(Map<String, String> headers) {}

  public record AddTag(String title) {}

  public record UpdateTag(String uuid, Map<String, Object> fields) {}

  public record DeleteTag(String uuid) {}

  public record RecordNotification(String uuid, String message, long at) {}

  @Override
  public SettingsState emptyState() {
    return SettingsState.fresh();
  }

  public ReadOnlyEffect<SettingsState> read() {
    return effects().reply(currentState());
  }

  public Effect<String> replace(Replace command) {
    return effects()
        .updateState(currentState().withSettings(command.settings()))
        .thenReply("ok");
  }

  public Effect<String> updateApplication(UpdateApplication command) {
    return effects()
        .updateState(currentState().withApplication(command.fields()))
        .thenReply("ok");
  }

  public Effect<String> updateRequests(UpdateRequests command) {
    return effects().updateState(currentState().withRequests(command.fields())).thenReply("ok");
  }

  public Effect<String> updateHeaders(UpdateHeaders command) {
    return effects().updateState(currentState().withHeaders(command.headers())).thenReply("ok");
  }

  /**
   * A tag, created only if one of that name does not already exist.
   *
   * <p>Names are compared without case or surrounding space, because the same tag typed twice
   * with different spacing would otherwise become two tags and a watch would show both.
   */
  public Effect<String> addTag(AddTag command) {
    String wanted = command.title() == null ? "" : command.title().strip();
    if (wanted.isEmpty()) {
      return effects().reply("");
    }
    for (Map.Entry<String, Map<String, Object>> entry : currentState().tags().entrySet()) {
      Object title = entry.getValue().get("title");
      if (title != null
          && String.valueOf(title).strip().equalsIgnoreCase(wanted)) {
        return effects().reply(entry.getKey());
      }
    }
    String uuid = UUID.randomUUID().toString();
    Map<String, Object> tag = WatchDefaults.create(uuid);
    tag.put("title", wanted);
    tag.put("date_created", System.currentTimeMillis() / 1000);
    tag.put("overrides_watch", false);
    tag.put("url_match_pattern", "");
    return effects().updateState(currentState().withTag(uuid, tag)).thenReply(uuid);
  }

  public Effect<String> updateTag(UpdateTag command) {
    Map<String, Object> existing = currentState().tags().get(command.uuid());
    if (existing == null) {
      return effects().error("no such tag");
    }
    Map<String, Object> merged = Fields.deepCopy(existing);
    merged.putAll(command.fields());
    merged.put("uuid", command.uuid());
    return effects().updateState(currentState().withTag(command.uuid(), merged)).thenReply("ok");
  }

  public Effect<String> deleteTag(DeleteTag command) {
    return effects().updateState(currentState().withoutTag(command.uuid())).thenReply("ok");
  }

  /** One line of the record of what was sent and whether it went. */
  public Effect<String> recordNotification(RecordNotification command) {
    return effects()
        .updateState(currentState().withNotificationLogEntry(command.message(), command.at()))
        .thenReply("ok");
  }

  /** The list of what was sent, newest first. */
  public ReadOnlyEffect<List<String>> notificationLog() {
    List<String> reversed = new ArrayList<>(currentState().notificationLog());
    java.util.Collections.reverse(reversed);
    return effects().reply(reversed);
  }
}
