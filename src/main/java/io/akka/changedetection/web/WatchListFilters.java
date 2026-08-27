package io.akka.changedetection.web;

import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchesView;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.Restock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which watches the operator is looking at.
 *
 * <p>One definition, used by the list, by the identifier feed behind "select everything
 * matching", and by the bulk actions. Three copies of this would drift, and the failure would
 * be silent and destructive: an action taken on a filtered list would apply to watches that
 * were never on it.
 */
public final class WatchListFilters {

  /** The query arguments that define a view. */
  public static final List<String> FILTER_KEYS =
      List.of("tag", "processor", "unread", "with_errors", "deals", "q");

  private WatchListFilters() {}

  /** One view: what it is narrowed to. */
  public record View(
      boolean withErrors,
      boolean unreadOnly,
      boolean deals,
      String processor,
      String tagUuid,
      String search) {}

  public static View of(Store store, Map<String, List<String>> arguments) {
    String requestedTag = first(arguments, "tag").toLowerCase(Locale.ROOT).strip();
    String search = first(arguments, "q").strip().toLowerCase(Locale.ROOT);
    return new View(
        first(arguments, "with_errors").equals("1"),
        first(arguments, "unread").equals("1"),
        first(arguments, "deals").equals("1"),
        first(arguments, "processor").strip(),
        resolveTag(store, requestedTag),
        search);
  }

  /** A tag named by its title or by its identifier, resolved to the identifier. */
  public static String resolveTag(Store store, String requested) {
    if (requested == null || requested.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, Map<String, Object>> entry : store.tags().entrySet()) {
      String title =
          String.valueOf(entry.getValue().getOrDefault("title", ""))
              .toLowerCase(Locale.ROOT)
              .strip();
      if (requested.equals(title) || requested.equals(entry.getKey())) {
        return entry.getKey();
      }
    }
    return null;
  }

  /** A price watch whose latest check saw the price fall. */
  public static boolean isDeal(Watch watch) {
    Map<String, Object> stored = watch.fields().map("restock");
    if (stored == null || stored.get("in_stock") == null) {
      return false;
    }
    Double percent = new Restock(stored).priceChangePercent();
    return percent != null && percent < 0;
  }

  /**
   * Whether a watch belongs to the tag being viewed.
   *
   * <p>Membership includes a tag applied by an address pattern, not only one assigned by hand:
   * a watch whose row shows a tag badge but which vanished from that tag's own view would be
   * telling the operator two different things.
   */
  public static boolean matchesTag(Store store, String uuid, View view) {
    return view.tagUuid() == null || store.tagsForWatch(uuid).containsKey(view.tagUuid());
  }

  public static boolean passesStatus(Watch watch, View view) {
    if (view.withErrors() && !hasError(watch)) {
      return false;
    }
    if (view.unreadOnly() && (watch.viewed() || watch.lastChanged() == 0)) {
      return false;
    }
    return !view.deals() || isDeal(watch);
  }

  public static boolean passesSearch(Watch watch, View view) {
    String search = view.search();
    if (search == null || search.isEmpty()) {
      return true;
    }
    String title = watch.fields().string("title", "").toLowerCase(Locale.ROOT);
    if (!title.isEmpty() && title.contains(search)) {
      return true;
    }
    if (watch.fields().string("url", "").toLowerCase(Locale.ROOT).contains(search)) {
      return true;
    }
    String error = watch.fields().string("last_error", "").toLowerCase(Locale.ROOT);
    return hasError(watch) && error.contains(search);
  }

  static boolean hasError(Watch watch) {
    Object error = watch.fields().get("last_error");
    return error != null && !Boolean.FALSE.equals(error) && !String.valueOf(error).isEmpty();
  }

  public static boolean matches(Store store, Watch watch, View view) {
    if (!matchesTag(store, watch.uuid(), view)) {
      return false;
    }
    if (!view.processor().isEmpty()
        && !view.processor().equals(watch.fields().string("processor", ""))) {
      return false;
    }
    return passesStatus(watch, view) && passesSearch(watch, view);
  }

  /** Every watch on this view, whichever page it would fall on. */
  public static List<String> matchingUuids(Store store, Map<String, List<String>> arguments) {
    View view = of(store, arguments);
    List<String> uuids = new ArrayList<>();
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      if (matches(store, entry.getValue(), view)) {
        uuids.add(entry.getKey());
      }
    }
    return uuids;
  }

  /** The filtering as query arguments, so an action can send the operator back to it. */
  public static Map<String, Object> asArguments(Map<String, List<String>> arguments) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : FILTER_KEYS) {
      String value = first(arguments, key);
      if (!value.isEmpty()) {
        out.put(key, value);
      }
    }
    return out;
  }

  static String first(Map<String, List<String>> arguments, String key) {
    List<String> values = arguments.get(key);
    return values == null || values.isEmpty() ? "" : values.get(0);
  }

  /** Whether the settings say a row should be listed by the page's own title. */
  public static boolean usePageTitle(Map<String, Object> application) {
    Object ui = application.get("ui");
    return ui instanceof Map<?, ?> map && Fields.truthy(map.get("use_page_title_in_list"));
  }

  /** The rows this view covers, in the order the store returns them. */
  public static List<WatchesView.WatchRow> rows(Store store, View view) {
    List<WatchesView.WatchRow> out = new ArrayList<>();
    for (WatchesView.WatchRow row : store.watchRows()) {
      if (matchesTag(store, row.uuid(), view)) {
        out.add(row);
      }
    }
    return out;
  }
}
