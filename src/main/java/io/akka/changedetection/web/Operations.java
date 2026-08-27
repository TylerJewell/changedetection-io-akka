package io.akka.changedetection.web;

import akka.javasdk.client.ComponentClient;
import io.akka.changedetection.application.SettingsEntity;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchEntity;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.forms.Choices;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.model.WatchDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The things a request can do to the stored watches.
 *
 * <p>Gathered here rather than written into each route because the same operations arrive by
 * three paths -- a form, the live connection, and the API -- and a rule enforced on one path
 * only is a rule that is not enforced.
 */
public final class Operations {

  private final ComponentClient componentClient;
  private final Store store;

  public Operations(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.store = new Store(componentClient);
  }

  public Store store() {
    return store;
  }

  /** What a bulk operation did, in the words the page shows. */
  public record Outcome(String message, String type) {}

  /**
   * Adds a watch.
   *
   * @return the new watch's identifier, or null when the address is one this may not fetch
   */
  public String addWatch(String url, String tagNames, Map<String, Object> extras) {
    Map<String, Object> apply = new LinkedHashMap<>(extras == null ? Map.of() : extras);
    List<String> tags = new ArrayList<>();
    Object givenTags = apply.get("tags");
    if (givenTags instanceof List<?> list) {
      for (Object item : list) {
        tags.add(String.valueOf(item));
      }
    }

    if (!UrlSafety.isSafeValidUrl(url, false)) {
      return null;
    }

    Integer limit = watchLimit();
    if (limit != null && store.watchUuids().size() >= limit) {
      return null;
    }

    if (tagNames != null && !tagNames.isBlank()) {
      for (String name : tagNames.split(",")) {
        String tagUuid = addTag(name);
        if (tagUuid != null) {
          tags.add(tagUuid);
        }
      }
    }
    // The same tag named twice -- by hand and by identifier -- must not be stored twice.
    apply.put("tags", new ArrayList<>(new LinkedHashSet<>(tags)));

    // The kind of watch becomes part of a file name later on, and this path does not check it
    // the way the API does: a value that is not one this rebuild knows is dropped rather than
    // stored, so it can never reach a path.
    Object processor = apply.get("processor");
    if (processor != null && !Choices.processorBadges().containsKey(String.valueOf(processor))) {
      apply.remove("processor");
    }

    for (String key :
        List.of(
            "uuid",
            "history",
            "last_checked",
            "last_changed",
            "newest_history_key",
            "previous_md5",
            "viewed")) {
      apply.remove(key);
    }
    if (!apply.containsKey("date_created") || apply.get("date_created") == null) {
      apply.put("date_created", System.currentTimeMillis() / 1000);
    }
    apply.put("url", url);

    String uuid = UUID.randomUUID().toString();
    Map<String, Object> fields = WatchDefaults.create(uuid);
    fields.putAll(apply);
    componentClient
        .forEventSourcedEntity(uuid)
        .method(WatchEntity::create)
        .invoke(new WatchEntity.Create(fields, System.currentTimeMillis() / 1000));
    return uuid;
  }

  /** A copy of a watch, without its history. */
  public String clone(String uuid) {
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return null;
    }
    Watch watch = state.asWatch();
    Map<String, Object> extras = new LinkedHashMap<>(watch.asMap());
    return addWatch(watch.fields().string("url", ""), "", extras);
  }

  public void delete(String uuid) {
    componentClient.forEventSourcedEntity(uuid).method(WatchEntity::delete).invoke();
    // A watch asked for and not yet started is still in the queue, and the queue is a set
    // of identifiers rather than of records -- so one deleted mid-wait stays in it forever,
    // drawn on the queue page as a row nothing will ever check.
    Site.unqueue(uuid);
    StreamHub.publish("watch_deleted", Map.of("uuid", uuid));
  }

  public void deleteAll() {
    for (String uuid : store.watchUuids()) {
      delete(uuid);
    }
  }

  public void clearHistory(String uuid) {
    List<Long> removed =
        componentClient.forEventSourcedEntity(uuid).method(WatchEntity::clearHistory).invoke();
    for (Long timestamp : removed) {
      store.deleteSnapshot(uuid, timestamp);
    }
  }

  public void markViewed(String uuid, long at) {
    componentClient
        .forEventSourcedEntity(uuid)
        .method(WatchEntity::markViewed)
        .invoke(new WatchEntity.MarkViewed(at));
  }

  public void update(String uuid, Map<String, Object> fields) {
    componentClient
        .forEventSourcedEntity(uuid)
        .method(WatchEntity::update)
        .invoke(new WatchEntity.Update(fields, System.currentTimeMillis() / 1000));
    StreamHub.publish("watch_update", Map.of("watch", Map.of("uuid", uuid)));
  }

  /**
   * Puts a watch back exactly as an archive holds it, with the snapshots it had.
   *
   * <p>Unlike an ordinary create, the identifier and every stored field come from the archive
   * rather than being made here -- restoring a backup that renamed everything would restore
   * nothing anybody could recognise, and any address in it saved earlier is already one this
   * service accepted once.
   */
  public void restoreWatch(
      String uuid, Map<String, Object> fields, Map<Long, String> snapshots) {
    if (store.watch(uuid).exists()) {
      delete(uuid);
    }
    long now = System.currentTimeMillis() / 1000;
    Map<String, Object> stored = new LinkedHashMap<>(fields);
    stored.put("uuid", uuid);
    componentClient
        .forEventSourcedEntity(uuid)
        .method(WatchEntity::create)
        .invoke(new WatchEntity.Create(stored, now));
    for (Map.Entry<Long, String> snapshot : snapshots.entrySet()) {
      store.saveSnapshot(uuid, snapshot.getKey(), snapshot.getValue());
      componentClient
          .forEventSourcedEntity(uuid)
          .method(WatchEntity::recordSnapshot)
          .invoke(
              new WatchEntity.RecordSnapshot(
                  snapshot.getKey(), "", snapshot.getValue().length()));
    }
  }

  /** A tag with this name, made if there is not one already. */
  public String addTag(String title) {
    String wanted = title == null ? "" : title.strip().toLowerCase(Locale.ROOT);
    if (wanted.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, Map<String, Object>> entry : store.tags().entrySet()) {
      String existing =
          String.valueOf(entry.getValue().getOrDefault("title", ""))
              .toLowerCase(Locale.ROOT)
              .strip();
      if (existing.equals(wanted)) {
        return entry.getKey();
      }
    }
    return componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::addTag)
        .invoke(new SettingsEntity.AddTag(title.strip()));
  }

  /** Asks for a check without waiting for it. */
  public void queueCheck(String uuid) {
    Site.queue(uuid);
    componentClient.forEventSourcedEntity(uuid).method(WatchEntity::noteStatus)
        .invoke(new WatchEntity.NoteStatus("Queued"));
  }

  /**
   * One operation applied to a set of watches.
   *
   * <p>Every branch reports how many it touched, because the page's only feedback is that
   * sentence and "nothing happened" and "it worked on nothing" have to look different.
   */
  public Outcome apply(String operation, List<String> uuids, String extra) {
    long now = System.currentTimeMillis() / 1000;
    switch (operation) {
      case "delete" -> {
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            delete(uuid);
          }
        }
        return new Outcome(uuids.size() + " watches deleted", "success");
      }
      case "pause", "unpause" -> {
        boolean paused = operation.equals("pause");
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            componentClient
                .forEventSourcedEntity(uuid)
                .method(WatchEntity::setPaused)
                .invoke(new WatchEntity.SetPaused(paused));
          }
        }
        return new Outcome(
            uuids.size() + (paused ? " watches paused" : " watches unpaused"), "success");
      }
      case "mark-viewed" -> {
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            markViewed(uuid, now);
          }
        }
        return new Outcome(uuids.size() + " watches updated", "success");
      }
      case "mute", "unmute" -> {
        boolean muted = operation.equals("mute");
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            componentClient
                .forEventSourcedEntity(uuid)
                .method(WatchEntity::setMuted)
                .invoke(new WatchEntity.SetMuted(muted));
          }
        }
        return new Outcome(
            uuids.size() + (muted ? " watches muted" : " watches un-muted"), "success");
      }
      case "recheck" -> {
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            queueCheck(uuid);
          }
        }
        return new Outcome(uuids.size() + " watches queued for rechecking", "success");
      }
      case "clear-errors" -> {
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("last_error", false);
            update(uuid, change);
          }
        }
        return new Outcome(uuids.size() + " watches errors cleared", "success");
      }
      case "clear-history" -> {
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            clearHistory(uuid);
          }
        }
        return new Outcome(uuids.size() + " watches cleared/reset.", "success");
      }
      case "notification-default" -> {
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("notification_title", null);
            change.put("notification_body", null);
            change.put("notification_urls", new ArrayList<>());
            change.put(
                "notification_format",
                io.akka.changedetection.model.Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT);
            update(uuid, change);
          }
        }
        return new Outcome(
            uuids.size() + " watches set to use default notification settings", "success");
      }
      case "set-fetch-backend" -> {
        List<String> valid = new ArrayList<>();
        for (String[] fetcher : Choices.fetchers()) {
          valid.add(fetcher[0]);
        }
        valid.add("system");
        if (!valid.contains(extra)) {
          return new Outcome("Invalid browser / fetch method selected", "error");
        }
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("fetch_backend", extra);
            update(uuid, change);
          }
        }
        return new Outcome(
            "Browser / fetch method updated on " + uuids.size() + " watches", "success");
      }
      case "set-proxy" -> {
        List<String> valid = new ArrayList<>();
        valid.add("");
        Map<String, Map<String, String>> proxies = new DatastoreView(store).proxies();
        if (proxies != null) {
          valid.addAll(proxies.keySet());
        }
        String chosen = extra == null ? "" : extra;
        if (!valid.contains(chosen)) {
          return new Outcome("Invalid proxy selected", "error");
        }
        for (String uuid : uuids) {
          if (store.watch(uuid).exists()) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("proxy", chosen);
            update(uuid, change);
          }
        }
        return new Outcome("Proxy updated on " + uuids.size() + " watches", "success");
      }
      case "assign-tag" -> {
        if (extra != null && !extra.isBlank()) {
          String tagUuid = addTag(extra);
          if (tagUuid != null) {
            for (String uuid : uuids) {
              WatchState state = store.watch(uuid);
              if (!state.exists()) {
                continue;
              }
              List<String> tags = new ArrayList<>(state.asWatch().fields().strings("tags"));
              tags.add(tagUuid);
              Map<String, Object> change = new LinkedHashMap<>();
              change.put("tags", tags);
              update(uuid, change);
            }
          }
        }
        return new Outcome(uuids.size() + " watches were tagged", "success");
      }
      default -> {
        return null;
      }
    }
  }

  static Integer watchLimit() {
    String configured = System.getenv("PAGE_WATCH_LIMIT");
    if (configured == null || configured.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(configured.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static boolean truthy(Object value) {
    return Fields.truthy(value);
  }
}
