package io.akka.changedetection.web;

import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.jinja.Filters;
import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A watch as the shipped templates read one.
 *
 * <p>Separate from the watch itself because most of what a row shows is not stored on a watch:
 * it is worked out from the settings around it, the address it would actually be fetched from,
 * and the errors it has collected. Keeping that here means the stored shape stays the stored
 * shape and the page's needs do not leak into it.
 */
public final class WatchView implements PyValue.Attributed {

  private final Watch watch;
  private final Environment environment;
  private final boolean checking;
  private final String checkStatus;
  private final boolean hasProxies;
  private final String faviconFilename;

  public WatchView(
      Watch watch,
      Environment environment,
      boolean checking,
      String checkStatus,
      boolean hasProxies,
      String faviconFilename) {
    this.watch = watch;
    this.environment = environment;
    this.checking = checking;
    this.checkStatus = checkStatus;
    this.hasProxies = hasProxies;
    this.faviconFilename = faviconFilename;
  }

  public Watch watch() {
    return watch;
  }

  public String uuid() {
    return watch.uuid();
  }

  /** The address as it would actually be fetched, with any template in it rendered. */
  public String link() {
    return watch.link(this::renderTemplate);
  }

  /** What the watch is listed under: its own title, else the page's, else its address. */
  public String label() {
    return watch.label(this::renderTemplate);
  }

  private String renderTemplate(String text) {
    try {
      return environment.renderString(text, new LinkedHashMap<>());
    } catch (RuntimeException e) {
      // A template that will not render leaves the address as written, which is what the row
      // shows and what the edit page then lets the operator fix.
      return text;
    }
  }

  public boolean hasRestockInfo() {
    Map<String, Object> restock = watch.fields().map("restock");
    return restock != null && restock.get("in_stock") != null;
  }

  /**
   * Whether the watch has steps that actually do something.
   *
   * <p>The unset first row and the implicit "go to the site" do not count: a watch showing the
   * steps icon because of an empty row would be telling the operator it does something it does
   * not.
   */
  public boolean hasBrowserSteps() {
    for (Map<String, Object> step : watch.fields().maps("browser_steps")) {
      Object operation = step.get("operation");
      String name = operation == null ? "" : String.valueOf(operation);
      if (!name.isEmpty() && !name.equals("Choose one") && !name.equals("Goto site")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Everything wrong with this watch, as markup.
   *
   * <p>The stored message came from a page the operator does not control, so it is escaped
   * before the links around it are added; the links are this interface's own.
   */
  public String errorTexts() {
    StringBuilder sb = new StringBuilder();
    String lastError = watch.fields().string("last_error", "");
    if (!lastError.isEmpty() && !lastError.equals("false") && !lastError.equals("null")) {
      String escaped = Filters.escapeHtml(lastError);
      if (escaped.contains("403")) {
        String settings =
            Routes.build("settings.settings_page", Map.of("uuid", watch.uuid()));
        sb.append(escaped)
            .append(" - <a href=\"")
            .append(settings)
            .append("\">")
            .append(
                hasProxies
                    ? "Try other proxies/location"
                    : "Try adding external proxies/locations")
            .append("</a>&nbsp;'");
      } else {
        sb.append(escaped);
      }
    }
    String notificationError = watch.fields().string("last_notification_error", "");
    if (!notificationError.isEmpty()
        && !notificationError.equals("false")
        && !notificationError.equals("null")) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append("<div class=\"notification-error\"><a href=\"")
          .append(Routes.build("settings.notification_logs", Map.of()))
          .append("\">")
          .append(Filters.escapeHtml(notificationError))
          .append("</a></div>");
    }
    return sb.toString();
  }

  @Override
  public Object attribute(String name) {
    switch (name) {
      case "link":
        return link();
      case "label":
        return label();
      case "uuid":
        return watch.uuid();
      case "has_restock_info":
        return hasRestockInfo();
      case "has_browser_steps":
        return hasBrowserSteps();
      case "get_fetch_backend":
        return watch.fetchBackend();
      case "checking":
      case "__checking":
        return checking;
      case "__check_status":
        return checkStatus == null ? "" : checkStatus;
      case "restock":
        return new RestockView(watch.fields().map("restock"));
      case "get_favicon_filename":
        return (PyValue.Callable) (positional, keyword) -> faviconFilename;
      case "compile_error_texts":
        return (PyValue.Callable) (positional, keyword) -> new PyValue.Markup(errorTexts());
      case "get":
        return (PyValue.Callable)
            (positional, keyword) -> {
              String key = positional.isEmpty() ? "" : PyValue.asString(positional.get(0));
              Object fallback = positional.size() > 1 ? positional.get(1) : null;
              Object value = attribute(key);
              if (value == PyValue.UNDEFINED || value == null) {
                return fallback;
              }
              return value;
            };
      case "history":
        return watch.history();
      case "fields":
        return watch.asMap();
      default:
        break;
    }
    Object value = watch.attribute(name);
    return value;
  }

  /** Whether the watch's own settings differ from the defaults enough to show a marker. */
  public boolean paused() {
    return Fields.truthy(watch.fields().get("paused"));
  }

  public List<Long> history() {
    return watch.history();
  }
}
