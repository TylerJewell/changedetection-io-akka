package io.akka.changedetection.web;

import io.akka.changedetection.application.Store;
import io.akka.changedetection.forms.Choices;
import io.akka.changedetection.jinja.PyValue;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The store as the shipped templates reach into it.
 *
 * <p>They read a nested settings tree and call a couple of lookups on it. Exposing exactly
 * those, rather than the store itself, keeps a template from being able to change anything.
 */
public final class DatastoreView implements PyValue.Attributed {

  private final Store store;
  private final Map<String, Object> settings;

  public DatastoreView(Store store) {
    this.store = store;
    this.settings = store.settings().settings();
  }

  @Override
  public Object attribute(String name) {
    return switch (name) {
      case "data" -> settings;
      case "proxy_list" -> proxies();
      case "get_all_tags_for_watch" ->
          (PyValue.Callable)
              (positional, keyword) ->
                  store.tagsForWatch(
                      positional.isEmpty() ? "" : PyValue.asString(positional.get(0)));
      case "unread_changes_count" -> store.unreadChangesCount();
      default -> PyValue.UNDEFINED;
    };
  }

  /** The configured proxies, or nothing at all when none are -- which hides the whole choice. */
  public Map<String, Map<String, String>> proxies() {
    Map<String, Map<String, String>> available = Choices.proxies(settings, proxiesFromFile());
    return available.isEmpty() ? null : available;
  }

  /**
   * Proxies configured outside the interface.
   *
   * <p>The original reads them from a file beside the data so that a deployment can supply them
   * without anyone signing in; the same file is read here.
   */
  static Map<String, Map<String, String>> proxiesFromFile() {
    java.nio.file.Path path = java.nio.file.Path.of(Site.datastorePath(), "proxies.json");
    if (!java.nio.file.Files.isRegularFile(path)) {
      return new LinkedHashMap<>();
    }
    try {
      com.fasterxml.jackson.databind.JsonNode root =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(path.toFile());
      Map<String, Map<String, String>> out = new LinkedHashMap<>();
      root.fields()
          .forEachRemaining(
              entry -> {
                Map<String, String> proxy = new LinkedHashMap<>();
                proxy.put("label", entry.getValue().path("label").asText(entry.getKey()));
                proxy.put("url", entry.getValue().path("url").asText(""));
                out.put(entry.getKey(), proxy);
              });
      return out;
    } catch (Exception e) {
      // A file that will not read leaves only what the interface itself configured.
      return new LinkedHashMap<>();
    }
  }
}
