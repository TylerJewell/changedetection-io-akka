package io.akka.changedetection.web;

import akka.javasdk.client.ComponentClient;
import io.akka.changedetection.application.SettingsEntity;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.forms.Choices;
import io.akka.changedetection.model.Watch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few things the programmatic interface needs that the pages already do somewhere else.
 *
 * <p>Each one is a page's behaviour reached without the page: deleting a tag also has to take it
 * off every watch, and editing one has to make those watches compare their next fetch afresh.
 * Doing either half without the other leaves the store inconsistent, so they live together here
 * rather than being written twice.
 */
final class ApiSupport {

  private ApiSupport() {}

  /** Deletes a tag and takes it off every watch carrying it. */
  static void removeTag(ComponentClient componentClient, Operations operations, String uuid) {
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::deleteTag)
        .invoke(new SettingsEntity.DeleteTag(uuid));
    TagsEndpoint.detach(operations, uuid);
  }

  /**
   * Forgets the stored body checksum of every watch carrying this tag.
   *
   * <p>A watch skips all of its work when the fetched body is byte-identical to the last one,
   * and a tag's settings are part of what that shortcut assumes has not moved. Without this, a
   * filter added to a tag would take effect only the next time each page happened to change.
   *
   * @return how many watches were touched
   */
  static int clearChecksumsForTag(Store store, String tagUuid) {
    int cleared = 0;
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      if (entry.getValue().fields().strings("tags").contains(tagUuid)) {
        store.saveSideStore(entry.getKey(), "raw-checksum", "");
        cleared++;
      }
    }
    return cleared;
  }

  /** The names a watch may choose a proxy by. */
  static List<String> proxyKeys(Store store) {
    Map<String, Object> settings = store.settings().settings();
    Map<String, Map<String, String>> available =
        Choices.proxies(settings, DatastoreView.proxiesFromFile());
    return new ArrayList<>(available.keySet());
  }

  /** A whole number read out of a stored field, or the fallback when it holds anything else. */
  static long longOf(Object value, long fallback) {
    Map<String, Object> holder = new LinkedHashMap<>();
    holder.put("value", value);
    return new io.akka.changedetection.model.Fields(holder).longValue("value", fallback);
  }
}
