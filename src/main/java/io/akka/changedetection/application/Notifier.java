package io.akka.changedetection.application;

import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.notification.NotificationContext;
import io.akka.changedetection.notification.NotificationFailed;
import io.akka.changedetection.notification.NotificationHandler;
import io.akka.changedetection.text.HtmlTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deciding whether to say anything, and saying it.
 *
 * <p>Where a notification goes, what it says and how it is written are each looked up the same
 * way: the watch's own setting, then the setting of any tag on it, then the global one. A watch
 * with nothing set inherits everything, which is how one destination configured once serves
 * every watch.
 *
 * <p>A muted watch or a muted tag stops the lookup dead rather than falling through, so muting
 * a tag mutes the watches under it even where the global setting would have sent something.
 */
public final class Notifier {

  private final Store store;
  private final NotificationHandler handler;

  public Notifier(Store store, Environment templates) {
    this.store = store;
    this.handler = new NotificationHandler(templates);
  }

  /**
   * The value in force for one of the notification settings.
   *
   * <p>Returns nothing when the watch or the tag that would have supplied it is muted, which is
   * the difference between "inherit" and "say nothing".
   */
  public Object cascadingValue(Watch watch, String name) {
    Object own = watch.fields().get(name);
    if (Fields.truthy(own) && !watch.fields().bool("notification_muted")) {
      if (name.equals("notification_format")
          && Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT.equals(own)) {
        Object global = store.application().get("notification_format");
        return Fields.truthy(global) ? global : AppSettings.DEFAULT_NOTIFICATION_FORMAT;
      }
      return own;
    }
    for (Map<String, Object> tag : store.tagsForWatch(watch).values()) {
      Object value = tag.get(name);
      if (Fields.truthy(value) && !Fields.truthy(tag.get("notification_muted"))) {
        return value;
      }
    }
    Object global = store.application().get(name);
    if (Fields.truthy(global)) {
      return global;
    }
    return switch (name) {
      case "notification_format" -> Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT;
      case "notification_body" -> AppSettings.DEFAULT_NOTIFICATION_BODY;
      case "notification_title" -> AppSettings.DEFAULT_NOTIFICATION_TITLE;
      default -> null;
    };
  }

  /** Whether a change on this watch would actually reach anyone. */
  public boolean willNotify(Watch watch) {
    if (watch.fields().bool("notification_muted")) {
      return false;
    }
    Object urls = cascadingValue(watch, "notification_urls");
    return urls instanceof List<?> list && !list.isEmpty();
  }

  public boolean contentChanged(String uuid, long timestamp) {
    return contentChanged(uuid, timestamp, Map.of());
  }

  /**
   * @param carried what the check worked out but has not written to the watch yet -- the AI
   *     summary and verdict, which are produced during the check and are needed by the
   *     notification that same check sends
   */
  public boolean contentChanged(String uuid, long timestamp, Map<String, Object> carried) {
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return false;
    }
    Watch watch = state.asWatch();
    Object urls = cascadingValue(watch, "notification_urls");
    if (!(urls instanceof List<?> list) || list.isEmpty()) {
      return false;
    }

    Map<String, Object> notification = baseContext(watch, uuid);
    notification.putAll(carried);
    notification.put("notification_urls", urls);
    notification.put("notification_title", cascadingValue(watch, "notification_title"));
    notification.put("notification_body", cascadingValue(watch, "notification_body"));
    notification.put("notification_format", cascadingValue(watch, "notification_format"));

    List<Long> history = new ArrayList<>(watch.history());
    if (!history.contains(timestamp)) {
      history.add(timestamp);
    }
    fillSnapshots(notification, uuid, history, history.size() - 2, history.size() - 1, watch);

    return send(uuid, notification);
  }

  /** The warning sent when a filter has not matched for several checks running. */
  public boolean filterFailure(String uuid) {
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return false;
    }
    Watch watch = state.asWatch();
    int threshold =
        new Fields(store.application())
            .integer(
                "filter_failure_notification_threshold_attempts",
                AppSettings.FILTER_FAILURE_THRESHOLD_DEFAULT);
    String filters = String.join(", ", watch.fields().strings("include_filters"));

    String body =
        "Hello,\n\nYour configured CSS/xPath filters of '"
            + filters
            + "' for {{watch_url}} did not appear on the page after "
            + threshold
            + " attempts.\n\nIt's possible the page changed layout and the filter needs updating "
            + "( Try the 'Visual Selector' tab )\n\nEdit link: {{base_url}}/edit/{{watch_uuid}}"
            + "\n\nThanks - Your omniscient changedetection.io installation.\n";

    Map<String, Object> notification = baseContext(watch, uuid);
    notification.put(
        "notification_title",
        "Changedetection.io - Alert - CSS/xPath filter was not present in the page");
    notification.put("notification_body", body);
    Object format = cascadingValue(watch, "notification_format");
    notification.put("notification_format", format);
    notification.put(
        "markup_text_links_to_html_links", String.valueOf(format).startsWith("html"));

    List<String> urls = watch.fields().strings("notification_urls");
    if (urls.isEmpty()) {
      urls = new Fields(store.application()).strings("notification_urls");
    }
    if (urls.isEmpty()) {
      return false;
    }
    notification.put("notification_urls", urls);
    return send(uuid, notification);
  }

  /** The warning sent when a scripted browser step has failed several checks running. */
  public boolean stepFailure(String uuid, int step) {
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return false;
    }
    Watch watch = state.asWatch();
    int threshold =
        new Fields(store.application())
            .integer(
                "filter_failure_notification_threshold_attempts",
                AppSettings.FILTER_FAILURE_THRESHOLD_DEFAULT);

    String body =
        "Hello,\n        \nYour configured browser step at position "
            + step
            + " for the web page watch {{watch_url}} did not appear on the page after "
            + threshold
            + " attempts, did the page change layout?\n\nThe element may have moved and needs "
            + "editing, or does it need a delay added?\n\nEdit link: "
            + "{{base_url}}/edit/{{watch_uuid}}\n\nThanks - Your omniscient changedetection.io "
            + "installation.\n";

    Map<String, Object> notification = baseContext(watch, uuid);
    notification.put(
        "notification_title",
        "Changedetection.io - Alert - Browser step at position " + step + " could not be run");
    notification.put("notification_body", body);
    Object format = cascadingValue(watch, "notification_format");
    notification.put("notification_format", format);
    notification.put(
        "markup_text_links_to_html_links", String.valueOf(format).startsWith("html"));

    List<String> urls = watch.fields().strings("notification_urls");
    if (urls.isEmpty()) {
      urls = new Fields(store.application()).strings("notification_urls");
    }
    if (urls.isEmpty()) {
      return false;
    }
    notification.put("notification_urls", urls);
    return send(uuid, notification);
  }

  /**
   * Sends one notification now, so an operator can see whether an address works.
   *
   * <p>Where the watch has fewer than two stored snapshots there is nothing to compare, and the
   * shared filling supplies an example pair -- a test that rendered an empty difference would
   * read as a broken template rather than as an empty history.
   */
  public List<NotificationHandler.Rendered> sendTest(
      Watch watch, Map<String, Object> notification, String examplePrevious,
      String exampleCurrent) {
    Map<String, Object> context = baseContext(watch, watch.uuid());
    context.putAll(notification);
    fillSnapshots(context, watch.uuid(), watch.history(), -2, -1, watch);
    List<NotificationHandler.Rendered> rendered =
        handler.process(context, store.application(), true);
    for (NotificationHandler.Rendered one : rendered) {
      record(watch.uuid(), "Sent '" + one.title() + "' to " + redact(one.url()));
    }
    return rendered;
  }

  /** Renders a notification against a watch without sending it, for the feed and for a test. */
  public List<NotificationHandler.Rendered> render(
      Watch watch, Map<String, Object> notification, int fromIndex, int toIndex) {
    Map<String, Object> context = baseContext(watch, watch.uuid());
    context.putAll(notification);
    fillSnapshots(context, watch.uuid(), watch.history(), fromIndex, toIndex, watch);
    return handler.process(context, store.application(), false);
  }

  private boolean send(String uuid, Map<String, Object> notification) {
    try {
      List<NotificationHandler.Rendered> rendered =
          handler.process(notification, store.application(), true);
      for (NotificationHandler.Rendered one : rendered) {
        record(uuid, "Sent '" + one.title() + "' to " + redact(one.url()));
      }
      return !rendered.isEmpty();
    } catch (NotificationFailed e) {
      record(uuid, "Failed: " + e.getMessage());
      store.client()
          .forEventSourcedEntity(uuid)
          .method(WatchEntity::recordCheck)
          .invoke(
              new WatchEntity.RecordCheck(
                  Map.of("last_notification_error", String.valueOf(e.getMessage())),
                  System.currentTimeMillis() / 1000));
      return false;
    }
  }

  private void record(String uuid, String message) {
    store.client()
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::recordNotification)
        .invoke(
            new SettingsEntity.RecordNotification(
                uuid, message, System.currentTimeMillis() / 1000));
    io.akka.changedetection.web.StreamHub.publish(
        "notification_event", java.util.Map.of("watch_uuid", uuid));
  }

  /** An address written into the record with its credentials taken out of it. */
  static String redact(String url) {
    int scheme = url.indexOf("://");
    int at = url.indexOf('@');
    if (scheme < 0 || at < 0 || at < scheme) {
      return url;
    }
    return url.substring(0, scheme + 3) + "***@" + url.substring(at + 1);
  }

  private Map<String, Object> baseContext(Watch watch, String uuid) {
    Map<String, Object> application = store.application();
    String baseUrl = String.valueOf(application.getOrDefault("active_base_url", ""));
    if (baseUrl == null || baseUrl.equals("null") || baseUrl.isBlank()) {
      Object configured = application.get("base_url");
      baseUrl = configured == null ? "" : String.valueOf(configured);
    }
    baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

    Map<String, Object> context = new LinkedHashMap<>(NotificationContext.emptyContext());
    context.put("base_url", baseUrl);
    context.put("diff_url", baseUrl + "diff/" + uuid);
    context.put("preview_url", baseUrl + "preview/" + uuid);
    context.put("edit_url", baseUrl + "edit/" + uuid);
    context.put("uuid", uuid);
    context.put("watch_uuid", uuid);
    context.put("watch_url", watch.fields().string("url", ""));
    context.put("watch_title", watch.label(text -> text));
    context.put("watch_label", watch.label(text -> text));
    context.put("watch_mime_type", watch.fields().string("content-type"));

    List<String> tagTitles = new ArrayList<>();
    for (Map<String, Object> tag : store.tagsForWatch(watch).values()) {
      Object title = tag.get("title");
      if (title != null) {
        tagTitles.add(String.valueOf(title));
      }
    }
    context.put("watch_tag", String.join(", ", tagTitles));
    context.put("restock", watch.fields().map("restock"));
    return context;
  }

  /**
   * The two versions a notification is about, and the text the trigger matched.
   *
   * <p>Where the watch has only one version -- a test notification, say -- two example texts
   * stand in, so that a body written with a difference token still renders something the person
   * testing it can read.
   */
  private void fillSnapshots(
      Map<String, Object> context,
      String uuid,
      List<Long> history,
      int fromIndex,
      int toIndex,
      Watch watch) {
    String previous =
        "Example text: example test\nExample text: change detection is cool\n"
            + "Example text: some more examples\n";
    String current =
        "Example text: example test\nExample text: change detection is fantastic\n"
            + "Example text: even more examples\nExample text: a lot more examples";

    if (history.size() > 1) {
      int from = Math.max(0, Math.min(fromIndex < 0 ? history.size() + fromIndex : fromIndex,
          history.size() - 1));
      int to = Math.max(0, Math.min(toIndex < 0 ? history.size() + toIndex : toIndex,
          history.size() - 1));
      previous = store.snapshot(uuid, history.get(from));
      current = store.snapshot(uuid, history.get(to));
      context.put("timestamp_from", history.get(from));
      context.put("timestamp_to", history.get(to));
      context.put(
          "change_datetime", new NotificationContext.FormattableTimestamp(history.get(to)));
    }

    context.put("prev_snapshot", previous);
    context.put("current_snapshot", current);

    List<String> triggers = watch.fields().strings("trigger_text");
    if (!triggers.isEmpty()) {
      String newest =
          history.isEmpty()
              ? current
              : store.snapshot(uuid, history.get(history.size() - 1));
      List<String> matched = HtmlTools.getTriggeredText(newest, triggers);
      context.put("triggered_text", String.join("\n", matched));
    }
  }
}
