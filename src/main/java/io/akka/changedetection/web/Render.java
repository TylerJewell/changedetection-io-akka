package io.akka.changedetection.web;

import akka.javasdk.http.RequestContext;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.TemplateEngine;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.jinja.Filters;
import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.llm.Evaluator;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.web.Session.Flash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A page, rendered from the shipped templates.
 *
 * <p>The templates are the original's, unchanged, so everything they reach for has to be here
 * under the name they reach for it by: the route builder, the translator, the signed-in flag,
 * the messages waiting to be shown. Anything missing shows up as a blank on the page rather
 * than as an error, which is why this errs towards providing a name even when it is unused.
 */
public final class Render {

  private Render() {}

  /** What one request knows about itself, gathered once and read by everything below. */
  public static final class Page {
    final RequestContext request;
    final Session session;
    final Store store;
    final String locale;
    final String path;
    final String blueprint;
    final Map<String, List<String>> query;
    final List<Flash> flashes = new ArrayList<>();

    Page(
        RequestContext request,
        Session session,
        Store store,
        String locale,
        String path,
        String blueprint) {
      this.request = request;
      this.session = session;
      this.store = store;
      this.locale = locale;
      this.path = path;
      this.blueprint = blueprint;
      this.query = Requests.query(request);
    }

    public Session session() {
      return session;
    }

    public String locale() {
      return locale;
    }

    public Store store() {
      return store;
    }

    public String path() {
      return path;
    }

    public Map<String, List<String>> query() {
      return query;
    }

    public String translate(String message) {
      return Translations.translate(locale, message);
    }
  }

  public static Page page(
      RequestContext request, Store store, String path, String blueprint) {
    Session session = Session.of(request);
    String locale =
        Translations.resolve(session.locale(), Requests.header(request, "Accept-Language"));
    return new Page(request, session, store, locale, path, blueprint);
  }

  /** Whether a password is set, which is what makes the interface ask for one. */
  public static boolean hasPassword(Map<String, Object> application) {
    Object stored = application.get("password");
    if (stored != null && !Boolean.FALSE.equals(stored) && !String.valueOf(stored).isEmpty()) {
      return true;
    }
    String fromEnvironment = System.getenv("SALTED_PASS");
    return fromEnvironment != null && !fromEnvironment.isBlank();
  }

  /**
   * Renders a template with everything the shipped ones expect to find.
   *
   * <p>Messages are taken from the session as part of rendering, which is what makes them
   * appear once; a caller that renders a page therefore has to send the session cookie back
   * with it, or the same message appears again on the next page.
   */
  public static String render(Page page, String template, Map<String, Object> variables) {
    Map<String, Object> application = page.store.application();
    return renderWith(page, environmentFor(page, application), template, variables);
  }

  /** The template environment for this request, which a caller may also render values with. */
  public static Environment environmentFor(Page page, Map<String, Object> application) {
    Environment environment = TemplateEngine.interfaceTemplates(application);
    installGlobals(environment, page, application);
    return environment;
  }

  public static String renderWith(
      Page page, Environment environment, String template, Map<String, Object> variables) {
    Map<String, Object> context = new LinkedHashMap<>(variables);
    context.putIfAbsent("extra_title", "");
    context.putIfAbsent("extra_classes", "");
    context.putIfAbsent("extra_stylesheets", new ArrayList<>());
    context.putIfAbsent("header", null);
    context.putIfAbsent("right_sticky", null);
    context.putIfAbsent("current_diff_url", null);
    context.putIfAbsent("active_tag_uuid", null);
    context.putIfAbsent("active_tag", null);
    context.putIfAbsent("rss_uuid_feed", null);
    context.putIfAbsent("bottom_horizontal_offscreen_contents", null);

    return environment.render(template, context);
  }

  static void installGlobals(
      Environment environment, Page page, Map<String, Object> application) {
    boolean hasPassword = hasPassword(application);
    boolean authenticated = page.session.loggedIn();

    environment.setTranslator(message -> Translations.translate(page.locale, message));

    environment.putGlobal(
        "_",
        (PyValue.Callable)
            (positional, keyword) -> {
              String message =
                  positional.isEmpty() ? "" : PyValue.asString(positional.get(0));
              return interpolate(Translations.translate(page.locale, message), keyword);
            });
    environment.putGlobal(
        "gettext",
        (PyValue.Callable)
            (positional, keyword) -> {
              String message =
                  positional.isEmpty() ? "" : PyValue.asString(positional.get(0));
              return interpolate(Translations.translate(page.locale, message), keyword);
            });
    environment.putGlobal(
        "pgettext",
        (PyValue.Callable)
            (positional, keyword) -> {
              String context = positional.isEmpty() ? "" : PyValue.asString(positional.get(0));
              String message =
                  positional.size() < 2 ? "" : PyValue.asString(positional.get(1));
              return interpolate(
                  Translations.translate(page.locale, context, message), keyword);
            });

    environment.putGlobal(
        "url_for",
        (PyValue.Callable)
            (positional, keyword) -> {
              String name = positional.isEmpty() ? "" : PyValue.asString(positional.get(0));
              Map<String, Object> arguments = new LinkedHashMap<>(keyword);
              // The original's templates ask for an absolute address in a feed link; the
              // interface is reached under whatever name the reader used, so the base is
              // taken from the request rather than configured.
              boolean external = PyValue.truthy(arguments.remove("_external"));
              arguments.remove("_anchor");
              String built = Routes.build(name, arguments);
              return external ? externalBase(page, application) + built : built;
            });

    environment.putGlobal(
        "filtered_action_url",
        (PyValue.Callable)
            (positional, keyword) -> {
              String name = positional.isEmpty() ? "" : PyValue.asString(positional.get(0));
              return Routes.build(name, currentFilters(page, keyword));
            });
    environment.putGlobal(
        "filter_url",
        (PyValue.Callable)
            (positional, keyword) ->
                Routes.build("watchlist.index", currentFilters(page, keyword)));

    environment.putGlobal(
        "csrf_token", (PyValue.Callable) (positional, keyword) -> Csrf.tokenFor(page.session));

    // Nothing when no language was chosen and none was asked for, which is what the page
    // prints in that case -- the language attribute reads as unset rather than as English.
    environment.putGlobal(
        "get_locale", (PyValue.Callable) (p, k) -> page.locale.isEmpty() ? null : page.locale);
    environment.putGlobal(
        "get_darkmode_state",
        (PyValue.Callable)
            (p, k) ->
                Fields.truthy(Requests.cookie(page.request, "css_dark_mode")) ? "true" : "false");
    environment.putGlobal(
        "get_css_version",
        (PyValue.Callable) (p, k) -> cssVersion(String.valueOf(page.store.settings()
            .settings().getOrDefault("app_guid", ""))));
    environment.putGlobal("get_html_head_extras", (PyValue.Callable) (p, k) -> "");
    environment.putGlobal(
        "get_sidebar_mode_class",
        (PyValue.Callable)
            (p, k) -> {
              Object ui = application.get("ui");
              String mode =
                  ui instanceof Map<?, ?> map && map.get("sidebar_mode") != null
                      ? String.valueOf(map.get("sidebar_mode"))
                      : "collapsed";
              return mode.equals("pinned")
                  ? "actionside-bar-on action-side-bar-expanded"
                  : "actionsidebar-minimal";
            });
    environment.putGlobal(
        "get_blueprint_class",
        (PyValue.Callable)
            (p, k) ->
                page.blueprint.isEmpty() ? "" : "blueprint-" + page.blueprint.replace('.', '-'));
    environment.putGlobal("get_socketio_path", (PyValue.Callable) (p, k) -> socketPrefix(page));

    environment.putGlobal(
        "is_safe_valid_url",
        (PyValue.Callable)
            (positional, keyword) ->
                !positional.isEmpty()
                    && UrlSafety.isSafeValidUrl(PyValue.asString(positional.get(0)), false));

    environment.putGlobal(
        "get_flashed_messages",
        (PyValue.Callable)
            (positional, keyword) -> {
              if (page.flashes.isEmpty()) {
                page.flashes.addAll(page.session.takeFlashes());
              }
              boolean withCategories = PyValue.truthy(keyword.get("with_categories"));
              List<Object> out = new ArrayList<>();
              for (Flash flash : page.flashes) {
                out.add(
                    withCategories
                        ? new PyValue.Tuple(flash.category(), flash.message())
                        : flash.message());
              }
              return out;
            });

    environment.putGlobal(
        "get_flag_for_locale",
        (PyValue.Callable)
            (positional, keyword) ->
                Translations.flagFor(
                    positional.isEmpty() ? "" : PyValue.asString(positional.get(0))));
    environment.putGlobal("available_languages", Translations.available());

    environment.putGlobal(
        "generate_tag_colors",
        (PyValue.Callable)
            (positional, keyword) ->
                badgeColours(positional.isEmpty() ? "" : PyValue.asString(positional.get(0))));
    environment.putGlobal(
        "wcag_text_color",
        (PyValue.Callable)
            (positional, keyword) ->
                contrastingText(positional.isEmpty() ? "" : PyValue.asString(positional.get(0))));

    environment.putGlobal(
        "get_all_tags_for_watch",
        (PyValue.Callable)
            (positional, keyword) -> {
              String uuid = positional.isEmpty() ? "" : PyValue.asString(positional.get(0));
              return page.store.tagsForWatch(uuid);
            });

    environment.putGlobal(
        "is_checking_now",
        (PyValue.Callable)
            (positional, keyword) -> {
              Object watch = positional.isEmpty() ? null : positional.get(0);
              return PyValue.truthy(PyValue.getAttribute(watch, "checking"));
            });
    environment.putGlobal(
        "get_current_worker_count",
        (PyValue.Callable) (p, k) -> workerCount(page.store));
    environment.putGlobal(
        "get_watch_queue_position",
        (PyValue.Callable)
            (positional, keyword) -> {
              Object watch = positional.isEmpty() ? null : positional.get(0);
              String uuid = PyValue.asString(PyValue.getAttribute(watch, "uuid"));
              int position = 0;
              for (String queued : Site.queued()) {
                if (queued.equals(uuid)) {
                  return position;
                }
                position++;
              }
              return -1;
            });
    environment.putGlobal(
        "get_worker_status_info", (PyValue.Callable) (p, k) -> workerStatus(page.store));

    environment.putGlobal("current_user", currentUser(authenticated || !hasPassword));
    environment.putGlobal("has_password", hasPassword);
    environment.putGlobal("session", sessionView(page));
    environment.putGlobal("request", requestView(page));
    environment.putGlobal("config", new LinkedHashMap<String, Object>());
    environment.putGlobal(
        "guid", String.valueOf(page.store.settings().settings().getOrDefault("app_guid", "")));
    environment.putGlobal(
        "llm_features_disabled", Evaluator.featuresDisabledByEnvironment());
    environment.putGlobal("app_rss_token", application.get("rss_access_token"));
    environment.putGlobal(
        "socket_io_enabled",
        !(application.get("ui") instanceof Map<?, ?> ui)
            || ui.get("socket_io_enabled") == null
            || Fields.truthy(ui.get("socket_io_enabled")));
  }

  /** The address the interface is reached at, taken from what the browser asked for. */
  static String externalBase(Page page, Map<String, Object> application) {
    Object configured = application.get("base_url");
    if (configured != null && !String.valueOf(configured).isBlank()
        && !String.valueOf(configured).equals("null")) {
      String base = String.valueOf(configured);
      return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
    String host = Requests.header(page.request, "Host");
    String forwardedProto = Requests.header(page.request, "X-Forwarded-Proto");
    String scheme = forwardedProto.isEmpty() ? "http" : forwardedProto;
    return host.isEmpty() ? "" : scheme + "://" + host;
  }

  static String socketPrefix(Page page) {
    if (System.getenv("USE_X_SETTINGS") == null) {
      return "";
    }
    return Requests.header(page.request, "X-Forwarded-Prefix");
  }

  /**
   * The current filtering, carried onto another link.
   *
   * <p>Carried so that an action taken from a filtered list applies to that list rather than to
   * everything -- "mark all as viewed" while looking at one tag must not mark the rest.
   */
  static Map<String, Object> currentFilters(Page page, Map<String, Object> overrides) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : page.query.entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      arguments.put(entry.getKey(), entry.getValue().get(0));
    }
    arguments.remove("page");
    arguments.putAll(overrides);
    arguments
        .entrySet()
        .removeIf(
            entry -> {
              Object value = entry.getValue();
              if (value == null) {
                return true;
              }
              String text = PyValue.asString(value);
              return text.isEmpty() || text.equals("0");
            });
    return arguments;
  }

  /**
   * The token that stamps a page's own forms.
   *
   * <p>Derived from the session rather than stored, so that it survives a restart exactly as
   * long as the session does and cannot be replayed against a different one.
   */
  public static final class Csrf {
    private Csrf() {}

    public static String tokenFor(Session session) {
      byte[] secret = Site.secret();
      byte[] material = new byte[secret.length + 8];
      System.arraycopy(secret, 0, material, 0, secret.length);
      byte[] marker =
          (session.loggedIn() ? "in" : "out").getBytes(StandardCharsets.UTF_8);
      System.arraycopy(marker, 0, material, secret.length, marker.length);
      try {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(material);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
          sb.append(String.format("%02x", b));
        }
        return sb.toString();
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 is unavailable", e);
      }
    }

    /** Whether a submission carried the stamp this session's forms are given. */
    public static boolean accepts(Session session, String token) {
      if (token == null || token.isEmpty()) {
        return false;
      }
      return MessageDigest.isEqual(
          tokenFor(session).getBytes(StandardCharsets.UTF_8),
          token.getBytes(StandardCharsets.UTF_8));
    }
  }

  static String cssVersion(String guid) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((guid + Site.VERSION).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      // Ten characters, and salted with this installation's own identifier: the raw version
      // in a page's asset links let an exposed instance be matched against known faults.
      return sb.substring(0, 10);
    } catch (NoSuchAlgorithmException e) {
      return "0000000000";
    }
  }

  /** Two colour schemes for a badge, derived from its name so they never move. */
  static Map<String, Object> badgeColours(String name) {
    long hash;
    try {
      byte[] digest =
          MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8));
      hash = 0;
      for (int index = 0; index < 4; index++) {
        hash = (hash << 8) | (digest[index] & 0xFFL);
      }
    } catch (NoSuchAlgorithmException e) {
      hash = 0;
    }
    long hue = hash % 360;
    long lightSaturation = 60 + (hash % 25);
    long lightLightness = 85 + (hash % 10);
    long textLightness = 25 + (hash % 15);
    long darkSaturation = 55 + (hash % 20);
    long darkLightness = 45 + (hash % 15);

    Map<String, Object> light = new LinkedHashMap<>();
    light.put("bg", "hsl(" + hue + ", " + lightSaturation + "%, " + lightLightness + "%)");
    light.put("color", "hsl(" + hue + ", 50%, " + textLightness + "%)");
    Map<String, Object> dark = new LinkedHashMap<>();
    dark.put("bg", "hsl(" + hue + ", " + darkSaturation + "%, " + darkLightness + "%)");
    dark.put("color", "#fff");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("light", light);
    out.put("dark", dark);
    return out;
  }

  /** Black or white, whichever a reader can actually read against the given colour. */
  static String contrastingText(String hexBackground) {
    String hex = hexBackground.startsWith("#") ? hexBackground.substring(1) : hexBackground;
    if (hex.length() != 6) {
      return "#000000";
    }
    double[] channels = new double[3];
    try {
      for (int index = 0; index < 3; index++) {
        channels[index] = Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16) / 255.0;
      }
    } catch (NumberFormatException e) {
      return "#000000";
    }
    double luminance =
        0.2126 * linear(channels[0]) + 0.7152 * linear(channels[1]) + 0.0722 * linear(channels[2]);
    return luminance > 0.179 ? "#000000" : "#ffffff";
  }

  private static double linear(double channel) {
    return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
  }

  static int workerCount(Store store) {
    Object workers = store.settings().requests().get("workers");
    return workers instanceof Number number ? number.intValue() : 5;
  }

  static Map<String, Object> workerStatus(Store store) {
    List<String> running = new ArrayList<>();
    for (var row : store.watchRows()) {
      if (row.checking()) {
        running.add(row.uuid());
      }
    }
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("count", workerCount(store));
    status.put("type", "async");
    status.put("active_workers", running.size());
    status.put("processing_watches", running);
    status.put("loop_running", true);
    return status;
  }

  /** Shaped like the object the templates ask about being signed in. */
  static PyValue.Attributed currentUser(boolean authenticated) {
    return name ->
        switch (name) {
          case "is_authenticated", "is_active" -> authenticated;
          case "is_anonymous" -> !authenticated;
          case "id" -> authenticated ? "defaultuser@changedetection.io" : null;
          default -> PyValue.UNDEFINED;
        };
  }

  static Map<String, Object> sessionView(Page page) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("locale", page.session.locale());
    view.put("share-link", page.session.shareLink().isEmpty() ? null : page.session.shareLink());
    return view;
  }

  static PyValue.Attributed requestView(Page page) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : page.query.entrySet()) {
      if (!entry.getValue().isEmpty()) {
        arguments.put(entry.getKey(), entry.getValue().get(0));
      }
    }
    return name ->
        switch (name) {
          case "path" -> page.path;
          case "args" -> arguments;
          case "blueprint" -> page.blueprint;
          case "method" -> "GET";
          default -> PyValue.UNDEFINED;
        };
  }

  /**
   * Fills the named holes in a translated phrase.
   *
   * <p>The phrases are written with named holes rather than positions, so that a translator may
   * reorder them; a hole with nothing to put in it is left as written rather than blanked, which
   * makes a mismatched translation visible instead of silently losing a word.
   */
  static String interpolate(String message, Map<String, Object> values) {
    if (values.isEmpty() || !message.contains("%(")) {
      return message;
    }
    String out = message;
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      String replacement = PyValue.asString(entry.getValue());
      out = out.replace("%(" + entry.getKey() + ")s", replacement);
      out = out.replace("%(" + entry.getKey() + ")d", replacement);
    }
    return out;
  }

  /** The label a watch is listed under, with any template in its title rendered. */
  public static String labelOf(Watch watch, Environment environment) {
    return watch.label(
        text -> {
          try {
            return environment.renderString(text, new LinkedHashMap<>());
          } catch (RuntimeException e) {
            return text;
          }
        });
  }

  static String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  static String escape(String value) {
    return Filters.escapeHtml(value == null ? "" : value);
  }
}
