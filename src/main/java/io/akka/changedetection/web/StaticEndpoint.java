package io.akka.changedetection.web;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The files a page asks for.
 *
 * <p>Three of the groups are not files on disk at all -- a watch's picture, its icon, and the
 * element map behind the visual selector -- and each of those is the watched page's own content,
 * so each is closed off when a password is set. The rest are the shipped assets and are open,
 * because the sign-in page needs its own stylesheet before anyone has signed in.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class StaticEndpoint extends AbstractHttpEndpoint {

  /** Only the two shapes the icon set ships, and only a plain name inside them. */
  private static final Pattern FLAG_PATH = Pattern.compile("^(1x1|4x3)/[a-z0-9-]+\\.svg$");

  private static final Pattern UNSAFE_GROUP = Pattern.compile("[^a-z0-9_-]+");

  private final ComponentClient componentClient;

  public StaticEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/static/flags/{shape}/{filename}")
  public HttpResponse flag(String shape, String filename) {
    String path = (shape + "/" + filename).toLowerCase(Locale.ROOT);
    if (!FLAG_PATH.matcher(path).matches()) {
      return Requests.notFound();
    }
    byte[] body = Assets.read("static/flags/" + path);
    if (body == null) {
      return Requests.notFound();
    }
    return Requests.bytes(
            StatusCodes.OK,
            ContentTypes.create(
                akka.http.javadsl.model.MediaTypes.applicationWithFixedCharset(
                    "svg+xml", akka.http.javadsl.model.HttpCharsets.UTF_8)),
            body)
        .addHeader(RawHeader.create("Cache-Control", "max-age=86400, public"));
  }

  @Get("/static/{group}/{filename}")
  public HttpResponse content(String group, String filename) {
    String cleaned = UNSAFE_GROUP.matcher(group.toLowerCase(Locale.ROOT)).replaceAll("");
    if (cleaned.isEmpty() || filename.isEmpty()) {
      return Requests.notFound();
    }

    Store store = new Store(componentClient);
    switch (cleaned) {
      case "screenshot":
        return screenshot(store, filename);
      case "favicon":
        return favicon(store, filename);
      case "visual_selector_data":
        return elements(store, filename);
      case "plugin":
        // Nothing extends this rebuild yet, so the group exists and is always empty.
        return Requests.notFound();
      default:
        break;
    }

    byte[] body = Assets.read("static/" + cleaned + "/" + filename);
    if (body == null) {
      return Requests.notFound();
    }
    return Requests.bytes(StatusCodes.OK, Requests.typeFor(filename), body);
  }

  private HttpResponse screenshot(Store store, String uuid) {
    Map<String, Object> application = store.application();
    if (Render.hasPassword(application)
        && !Session.of(requestContext()).loggedIn()
        && !io.akka.changedetection.model.Fields.truthy(application.get("shared_diff_access"))) {
      return Requests.text(StatusCodes.FORBIDDEN, "Forbidden");
    }
    boolean wantsError =
        !Requests.queryValue(requestContext(), "error_screenshot", "").isEmpty();
    String stored =
        store.sideStore(uuid, wantsError ? "last-error-screenshot.png" : "last-screenshot.png");
    if (stored == null || stored.isEmpty()) {
      return Requests.notFound();
    }
    return Requests.bytes(
            StatusCodes.OK,
            ContentTypes.create(akka.http.javadsl.model.MediaTypes.IMAGE_PNG),
            decode(stored))
        .addHeader(RawHeader.create("Cache-Control", "no-cache, no-store, must-revalidate"))
        .addHeader(RawHeader.create("Pragma", "no-cache"))
        .addHeader(RawHeader.create("Expires", "0"));
  }

  private HttpResponse favicon(Store store, String uuid) {
    Map<String, Object> application = store.application();
    if (Render.hasPassword(application) && !Session.of(requestContext()).loggedIn()) {
      return Requests.text(StatusCodes.FORBIDDEN, "Forbidden");
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return Requests.notFound();
    }
    String name = store.sideStore(uuid, "favicon-name");
    if (name == null || name.isEmpty()) {
      return Requests.notFound();
    }
    String stored = store.sideStore(uuid, "favicon");
    if (stored == null || stored.isEmpty()) {
      return Requests.notFound();
    }
    ContentType type = Requests.typeFor(name);
    // A page that answers a favicon request with its own error page would otherwise be shown
    // as a broken picture on every row; text is refused rather than served.
    if (type.toString().startsWith("text/")) {
      return Requests.notFound();
    }
    return Requests.bytes(StatusCodes.OK, type, decode(stored))
        .addHeader(RawHeader.create("Cache-Control", "max-age=300, must-revalidate"));
  }

  private HttpResponse elements(Store store, String uuid) {
    Map<String, Object> application = store.application();
    if (Render.hasPassword(application) && !Session.of(requestContext()).loggedIn()) {
      return Requests.text(StatusCodes.FORBIDDEN, "Forbidden");
    }
    String stored = store.sideStore(uuid, "elements");
    if (stored == null || stored.isEmpty()) {
      return Requests.notFound();
    }
    return Requests.bytes(
            StatusCodes.OK,
            ContentTypes.APPLICATION_JSON,
            stored.getBytes(StandardCharsets.UTF_8))
        .addHeader(RawHeader.create("Cache-Control", "no-cache, no-store, must-revalidate"))
        .addHeader(RawHeader.create("Pragma", "no-cache"))
        .addHeader(RawHeader.create("Expires", "0"));
  }

  private static byte[] decode(String stored) {
    try {
      return Base64.getDecoder().decode(stored);
    } catch (IllegalArgumentException e) {
      return stored.getBytes(StandardCharsets.UTF_8);
    }
  }

  /** The shipped files, read from where they are packaged. */
  static final class Assets {
    private Assets() {}

    static byte[] read(String path) {
      // A path is never joined from anything a request supplied without being cleaned first;
      // the two callers above do that. This only refuses the obvious.
      if (path.contains("..")) {
        return null;
      }
      try (InputStream stream =
          StaticEndpoint.class.getResourceAsStream("/changedetection/" + path)) {
        if (stream == null) {
          return null;
        }
        return stream.readAllBytes();
      } catch (IOException e) {
        return null;
      }
    }
  }
}
