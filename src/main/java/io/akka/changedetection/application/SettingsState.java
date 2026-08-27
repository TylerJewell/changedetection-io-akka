package io.akka.changedetection.application;

import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Fields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The settings tree, the tags, and the record of what has been sent. */
public record SettingsState(
    Map<String, Object> settings,
    Map<String, Map<String, Object>> tags,
    List<String> notificationLog) {

  /**
   * How many lines of the sending record are kept.
   *
   * <p>Bounded because this is one piece of state and it would otherwise grow with every
   * notification ever sent, without limit, in a store that replicates state whole.
   */
  private static final int NOTIFICATION_LOG_LIMIT = 200;

  public static SettingsState fresh() {
    return new SettingsState(AppSettings.create(), new LinkedHashMap<>(), List.of());
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> application() {
    Map<String, Object> tree = (Map<String, Object>) settings.get("settings");
    return (Map<String, Object>) tree.get("application");
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> requests() {
    Map<String, Object> tree = (Map<String, Object>) settings.get("settings");
    return (Map<String, Object>) tree.get("requests");
  }

  @SuppressWarnings("unchecked")
  public Map<String, String> headers() {
    Map<String, Object> tree = (Map<String, Object>) settings.get("settings");
    Map<String, Object> raw = (Map<String, Object>) tree.get("headers");
    Map<String, String> out = new LinkedHashMap<>();
    if (raw != null) {
      for (Map.Entry<String, Object> entry : raw.entrySet()) {
        out.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> ui() {
    Object value = application().get("ui");
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
  }

  public SettingsState withSettings(Map<String, Object> replacement) {
    return new SettingsState(Fields.deepCopy(replacement), tags, notificationLog);
  }

  @SuppressWarnings("unchecked")
  public SettingsState withApplication(Map<String, Object> changes) {
    Map<String, Object> copy = Fields.deepCopy(settings);
    Map<String, Object> tree = (Map<String, Object>) copy.get("settings");
    Map<String, Object> application = (Map<String, Object>) tree.get("application");
    application.putAll(changes);
    return new SettingsState(copy, tags, notificationLog);
  }

  @SuppressWarnings("unchecked")
  public SettingsState withRequests(Map<String, Object> changes) {
    Map<String, Object> copy = Fields.deepCopy(settings);
    Map<String, Object> tree = (Map<String, Object>) copy.get("settings");
    Map<String, Object> requests = (Map<String, Object>) tree.get("requests");
    requests.putAll(changes);
    return new SettingsState(copy, tags, notificationLog);
  }

  @SuppressWarnings("unchecked")
  public SettingsState withHeaders(Map<String, String> replacement) {
    Map<String, Object> copy = Fields.deepCopy(settings);
    Map<String, Object> tree = (Map<String, Object>) copy.get("settings");
    tree.put("headers", new LinkedHashMap<String, Object>(replacement));
    return new SettingsState(copy, tags, notificationLog);
  }

  public SettingsState withTag(String uuid, Map<String, Object> tag) {
    Map<String, Map<String, Object>> copy = new LinkedHashMap<>(tags);
    copy.put(uuid, tag);
    return new SettingsState(settings, copy, notificationLog);
  }

  public SettingsState withoutTag(String uuid) {
    Map<String, Map<String, Object>> copy = new LinkedHashMap<>(tags);
    copy.remove(uuid);
    return new SettingsState(settings, copy, notificationLog);
  }

  public SettingsState withNotificationLogEntry(String message, long at) {
    List<String> log = new ArrayList<>(notificationLog);
    log.add(message);
    while (log.size() > NOTIFICATION_LOG_LIMIT) {
      log.remove(0);
    }
    return new SettingsState(settings, tags, List.copyOf(log));
  }
}
