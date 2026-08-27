package io.akka.changedetection.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every watch, as the list, the search and the scheduler need to see it.
 *
 * <p>The scheduler reads this rather than each watch in turn, because deciding what is due
 * means looking at every watch every second and the answer depends on four fields out of
 * seventy.
 *
 * <p>Fields that a watch genuinely may not have are declared as optional. A row whose plain
 * text field is absent stops the view's own update stream, and that failure is silent: every
 * query then answers with nothing at all rather than with an error.
 */
@Component(id = "watches")
public class WatchesView extends View {

  /** One watch, reduced to what a list or a scheduler decision needs. */
  public record WatchRow(
      String uuid,
      Optional<String> url,
      Optional<String> title,
      Optional<String> pageTitle,
      Optional<String> processor,
      Optional<String> lastError,
      Optional<String> tagsCsv,
      boolean paused,
      boolean muted,
      long lastChecked,
      long lastChanged,
      long lastViewed,
      long dateCreated,
      long intervalSeconds,
      boolean useDefaultInterval,
      int historyCount,
      boolean checking) {}

  public record WatchRows(List<WatchRow> watches) {}

  @Consume.FromEventSourcedEntity(WatchEntity.class)
  public static class Watches extends TableUpdater<WatchRow> {

    public Effect<WatchRow> onEvent(WatchEvent event) {
      if (event instanceof WatchEvent.Deleted) {
        return effects().deleteRow();
      }
      String uuid = updateContext().eventSubject().orElse("");
      WatchRow row = rowState();
      if (row == null && !(event instanceof WatchEvent.Created)) {
        return effects().ignore();
      }
      return effects().updateRow(rowFor(uuid, row, event));
    }

    private static WatchRow rowFor(String uuid, WatchRow previous, WatchEvent event) {
      // The row is rebuilt from the previous row plus this event rather than by reading the
      // entity, because the view sees events and not state.
      if (event instanceof WatchEvent.Created created) {
        return fromFields(uuid, created.fields(), List.of(), false);
      }
      Map<String, Object> fields = previousFields(previous);
      long lastChecked = previous.lastChecked();
      int historyCount = previous.historyCount();
      long lastChanged = previous.lastChanged();
      boolean checking = previous.checking();

      switch (event) {
        case WatchEvent.Edited e -> fields.putAll(e.fields());
        case WatchEvent.Checked e -> {
          fields.putAll(e.fields());
          if (e.fields().containsKey("last_checked")) {
            lastChecked = asLong(e.fields().get("last_checked"));
          }
        }
        case WatchEvent.SnapshotRecorded e -> {
          historyCount = historyCount + 1;
          lastChanged = historyCount <= 1 ? 0 : e.timestamp();
        }
        case WatchEvent.HistoryTrimmed e -> {
          historyCount = e.keptTimestamps().size();
          lastChanged =
              e.keptTimestamps().size() <= 1
                  ? 0
                  : e.keptTimestamps().get(e.keptTimestamps().size() - 1);
        }
        case WatchEvent.HistoryCleared e -> {
          historyCount = 0;
          lastChanged = 0;
          fields.put("last_viewed", 0);
        }
        case WatchEvent.Viewed e -> fields.put("last_viewed", e.timestamp());
        case WatchEvent.PauseChanged e -> fields.put("paused", e.paused());
        case WatchEvent.MuteChanged e -> fields.put("notification_muted", e.muted());
        case WatchEvent.CheckStarted e -> {
          lastChecked = e.at();
          checking = true;
        }
        case WatchEvent.CheckFinished e -> checking = false;
        default -> {
          // Nothing else changes what a list or the scheduler reads.
        }
      }

      WatchRow rebuilt = fromFields(uuid, fields, List.of(), checking);
      return new WatchRow(
          rebuilt.uuid(), rebuilt.url(), rebuilt.title(), rebuilt.pageTitle(),
          rebuilt.processor(), rebuilt.lastError(), rebuilt.tagsCsv(), rebuilt.paused(),
          rebuilt.muted(), lastChecked, lastChanged, rebuilt.lastViewed(), rebuilt.dateCreated(),
          rebuilt.intervalSeconds(), rebuilt.useDefaultInterval(), historyCount, checking);
    }

    private static Map<String, Object> previousFields(WatchRow row) {
      Map<String, Object> fields = new java.util.LinkedHashMap<>();
      fields.put("url", row.url().orElse(""));
      row.title().ifPresent(value -> fields.put("title", value));
      row.pageTitle().ifPresent(value -> fields.put("page_title", value));
      fields.put("processor", row.processor().orElse("text_json_diff"));
      row.lastError().ifPresent(value -> fields.put("last_error", value));
      fields.put("paused", row.paused());
      fields.put("notification_muted", row.muted());
      fields.put("last_viewed", row.lastViewed());
      fields.put("date_created", row.dateCreated());
      fields.put("time_between_check_use_default", row.useDefaultInterval());
      fields.put("__interval_seconds", row.intervalSeconds());
      fields.put("__tags_csv", row.tagsCsv().orElse(""));
      return fields;
    }

    private static WatchRow fromFields(
        String uuid, Map<String, Object> raw, List<String> unusedTags, boolean checking) {
      Fields fields = new Fields(raw);
      long interval =
          raw.containsKey("time_between_check")
              ? Watch.thresholdSeconds(fields.map("time_between_check"))
              : fields.longValue("__interval_seconds", 0);
      String tagsCsv =
          raw.containsKey("tags")
              ? String.join(",", fields.strings("tags"))
              : fields.string("__tags_csv", "");
      Object lastError = raw.get("last_error");
      String errorText =
          lastError == null || Boolean.FALSE.equals(lastError) ? null : String.valueOf(lastError);
      return new WatchRow(
          uuid,
          Optional.ofNullable(blankToNull(fields.string("url"))),
          Optional.ofNullable(blankToNull(fields.string("title"))),
          Optional.ofNullable(blankToNull(fields.string("page_title"))),
          Optional.ofNullable(blankToNull(fields.string("processor", "text_json_diff"))),
          Optional.ofNullable(blankToNull(errorText)),
          Optional.ofNullable(blankToNull(tagsCsv)),
          fields.bool("paused"),
          fields.bool("notification_muted"),
          fields.longValue("last_checked", 0),
          0,
          fields.longValue("last_viewed", 0),
          fields.longValue("date_created", 0),
          interval,
          fields.bool("time_between_check_use_default", true),
          0,
          checking);
    }

    private static String blankToNull(String value) {
      return value == null || value.isEmpty() ? null : value;
    }

    private static long asLong(Object value) {
      if (value instanceof Number n) {
        return n.longValue();
      }
      try {
        return Long.parseLong(String.valueOf(value));
      } catch (RuntimeException e) {
        return 0;
      }
    }
  }

  @Query("SELECT * AS watches FROM watches")
  public QueryEffect<WatchRows> all() {
    return queryResult();
  }

  @Query("SELECT * AS watches FROM watches WHERE paused = false")
  public QueryEffect<WatchRows> active() {
    return queryResult();
  }

  @Query("SELECT * AS watches FROM watches WHERE processor = :processor")
  public QueryEffect<WatchRows> byProcessor(String processor) {
    return queryResult();
  }
}
