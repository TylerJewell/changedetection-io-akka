package io.akka.changedetection.application;

import akka.javasdk.client.ComponentClient;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One place to ask about watches, tags, settings and stored versions.
 *
 * <p>The rest of the system is written against this rather than against the components, because
 * almost every question -- which tags apply to a watch, what the effective interval is, what
 * the last stored version said -- needs two or three of them at once, and the answers have to
 * agree with each other.
 */
public final class Store {

  private final ComponentClient componentClient;

  public Store(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public ComponentClient client() {
    return componentClient;
  }

  // -------------------------------------------------------------- settings

  public SettingsState settings() {
    return componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::read)
        .invoke();
  }

  public Map<String, Object> application() {
    return settings().application();
  }

  /**
   * What the AI evaluation reads and writes.
   *
   * <p>Handed out as a narrow view rather than the whole store because the evaluation is
   * advisory and must not be able to touch a watch's own settings.
   */
  public io.akka.changedetection.llm.Evaluator.Surroundings llmSurroundings() {
    Store store = this;
    return new io.akka.changedetection.llm.Evaluator.Surroundings() {
      @Override
      public Map<String, Object> application() {
        return store.application();
      }

      @Override
      public Map<String, Map<String, Object>> tags() {
        return store.tags();
      }

      @Override
      public void saveLlmSettings(Map<String, Object> llm) {
        store
            .client()
            .forKeyValueEntity(SettingsEntity.ID)
            .method(SettingsEntity::updateApplication)
            .invoke(new SettingsEntity.UpdateApplication(Map.of("llm", llm)));
      }
    };
  }

  // --------------------------------------------------------------- watches

  public WatchState watch(String uuid) {
    return componentClient
        .forEventSourcedEntity(uuid)
        .method(WatchEntity::read)
        .invoke();
  }

  public List<WatchesView.WatchRow> watchRows() {
    return componentClient.forView().method(WatchesView::all).invoke().watches();
  }

  public List<String> watchUuids() {
    List<String> uuids = new ArrayList<>();
    for (WatchesView.WatchRow row : watchRows()) {
      uuids.add(row.uuid());
    }
    return uuids;
  }

  /** Every watch as the domain object, which the list page and the feed both need. */
  public Map<String, Watch> allWatches() {
    Map<String, Watch> out = new LinkedHashMap<>();
    for (String uuid : watchUuids()) {
      WatchState state = watch(uuid);
      if (state.exists()) {
        out.put(uuid, state.asWatch());
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ tags

  public Map<String, Map<String, Object>> tags() {
    return settings().tags();
  }

  /**
   * The tags that apply to a watch: the ones attached to it, and any whose address pattern
   * matches its address.
   *
   * <p>The second kind is the reason this cannot be read off the watch alone. A tag that
   * matches by pattern shows on the watch's row and filters it, so leaving it out would make
   * the row and the filtered list disagree.
   */
  public Map<String, Map<String, Object>> tagsForWatch(String watchUuid) {
    WatchState state = watch(watchUuid);
    if (!state.exists()) {
      return new LinkedHashMap<>();
    }
    return tagsForWatch(state.asWatch());
  }

  public Map<String, Map<String, Object>> tagsForWatch(Watch watch) {
    Map<String, Map<String, Object>> all = tags();
    Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    List<String> attached = watch.fields().strings("tags");
    for (String uuid : attached) {
      Map<String, Object> tag = all.get(uuid);
      if (tag != null) {
        out.put(uuid, tag);
      }
    }
    String url = watch.fields().string("url", "");
    if (!url.isEmpty()) {
      for (Map.Entry<String, Map<String, Object>> entry : all.entrySet()) {
        if (out.containsKey(entry.getKey())) {
          continue;
        }
        if (matchesUrl(entry.getValue(), url)) {
          out.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return out;
  }

  /**
   * Whether a tag claims a watch by its address.
   *
   * <p>A pattern with a wildcard is matched as a pattern over the whole address; anything else
   * is matched as a piece of it, without case. The two are different enough that a tag written
   * as {@code shop.example} matches every page on that host, and one written as
   * {@code https://shop.example/*} matches only the pages under it.
   */
  static boolean matchesUrl(Map<String, Object> tag, String url) {
    Object raw = tag.get("url_match_pattern");
    String pattern = raw == null ? "" : String.valueOf(raw).strip();
    if (pattern.isEmpty() || url.isEmpty()) {
      return false;
    }
    if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('[') >= 0) {
      return globMatches(pattern.toLowerCase(java.util.Locale.ROOT),
          url.toLowerCase(java.util.Locale.ROOT));
    }
    return url.toLowerCase(java.util.Locale.ROOT)
        .contains(pattern.toLowerCase(java.util.Locale.ROOT));
  }

  private static boolean globMatches(String pattern, String value) {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append('.');
        case '[' -> {
          int close = pattern.indexOf(']', i);
          if (close < 0) {
            regex.append("\\[");
          } else {
            String set = pattern.substring(i + 1, close);
            regex.append('[');
            if (set.startsWith("!")) {
              regex.append('^').append(set.substring(1));
            } else {
              regex.append(set);
            }
            regex.append(']');
            i = close;
          }
        }
        default -> regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
      }
    }
    return value.matches(regex.toString());
  }

  /** The values the tags on a watch supply for one field, in the order they are read. */
  public List<String> tagOverrides(String watchUuid, String attribute) {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> tag : tagsForWatch(watchUuid).values()) {
      Object value = tag.get(attribute);
      if (value instanceof List<?> list && !list.isEmpty()) {
        for (Object item : list) {
          if (item != null) {
            out.add(String.valueOf(item));
          }
        }
      }
    }
    return out;
  }

  // ------------------------------------------------------------- snapshots

  public String snapshot(String watchUuid, long timestamp) {
    return componentClient
        .forKeyValueEntity(SnapshotEntity.id(watchUuid, timestamp, ""))
        .method(SnapshotEntity::read)
        .invoke();
  }

  public void saveSnapshot(String watchUuid, long timestamp, String text) {
    componentClient
        .forKeyValueEntity(SnapshotEntity.id(watchUuid, timestamp, ""))
        .method(SnapshotEntity::store)
        .invoke(new SnapshotEntity.Store(text, ""));
  }

  public void deleteSnapshot(String watchUuid, long timestamp) {
    componentClient
        .forKeyValueEntity(SnapshotEntity.id(watchUuid, timestamp, ""))
        .method(SnapshotEntity::remove)
        .invoke();
  }

  /** A blob kept beside a watch rather than beside one of its versions. */
  public String sideStore(String watchUuid, String kind) {
    return componentClient
        .forKeyValueEntity(SnapshotEntity.id(watchUuid, 0, kind))
        .method(SnapshotEntity::read)
        .invoke();
  }

  public void saveSideStore(String watchUuid, String kind, String text) {
    componentClient
        .forKeyValueEntity(SnapshotEntity.id(watchUuid, 0, kind))
        .method(SnapshotEntity::store)
        .invoke(new SnapshotEntity.Store(text, kind));
  }

  public void deleteSideStore(String watchUuid, String kind) {
    componentClient
        .forKeyValueEntity(SnapshotEntity.id(watchUuid, 0, kind))
        .method(SnapshotEntity::remove)
        .invoke();
  }

  /**
   * Remembers that a blob of this kind exists for this watch.
   *
   * <p>Nothing can list the keys of a key-value store, so anything that has to be cleared
   * later -- the cached AI summaries -- has to be written down when it is made.
   */
  public void noteSideStore(String watchUuid, String kind) {
    String index = sideStore(watchUuid, "side-store-index");
    java.util.LinkedHashSet<String> kinds = new java.util.LinkedHashSet<>();
    if (index != null && !index.isEmpty()) {
      kinds.addAll(java.util.List.of(index.split("\n")));
    }
    if (kinds.add(kind)) {
      saveSideStore(watchUuid, "side-store-index", String.join("\n", kinds));
    }
  }

  /** Every blob kind noted for this watch whose name begins with the given prefix. */
  public List<String> notedSideStores(String watchUuid, String prefix) {
    String index = sideStore(watchUuid, "side-store-index");
    List<String> out = new ArrayList<>();
    if (index == null || index.isEmpty()) {
      return out;
    }
    for (String kind : index.split("\n")) {
      if (!kind.isEmpty() && kind.startsWith(prefix)) {
        out.add(kind);
      }
    }
    return out;
  }

  /** The number of watches with something the operator has not looked at. */
  public int unreadChangesCount() {
    int count = 0;
    for (WatchesView.WatchRow row : watchRows()) {
      if (row.historyCount() >= 2 && row.lastChanged() > row.lastViewed()) {
        count++;
      }
    }
    return count;
  }
}
