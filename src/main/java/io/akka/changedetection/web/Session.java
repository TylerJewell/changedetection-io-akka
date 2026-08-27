package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.http.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What the browser carries between requests: whether it is signed in, which language it asked
 * for, and the messages waiting to be shown once.
 *
 * <p>Held in a signed cookie rather than on the server. Nothing secret is in it -- a message,
 * a language, and the fact of being signed in -- and the signature is what stops any of those
 * from being edited by hand. A cookie whose signature does not check out is treated as absent
 * rather than as an error, which is what an expired secret or a restart looks like.
 */
public final class Session {

  public static final String COOKIE_NAME = "changedetection_session";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, Object> values;

  private Session(Map<String, Object> values) {
    this.values = values;
  }

  public static Session of(RequestContext context) {
    return read(Requests.cookie(context, COOKIE_NAME));
  }

  static Session read(String cookie) {
    if (cookie == null || cookie.isEmpty()) {
      return new Session(new LinkedHashMap<>());
    }
    int dot = cookie.lastIndexOf('.');
    if (dot <= 0) {
      return new Session(new LinkedHashMap<>());
    }
    String payload = cookie.substring(0, dot);
    String signature = cookie.substring(dot + 1);
    if (!MessageDigest.isEqual(
        sign(payload).getBytes(StandardCharsets.UTF_8),
        signature.getBytes(StandardCharsets.UTF_8))) {
      return new Session(new LinkedHashMap<>());
    }
    try {
      byte[] raw = Base64.getUrlDecoder().decode(payload);
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = MAPPER.readValue(raw, Map.class);
      return new Session(parsed == null ? new LinkedHashMap<>() : parsed);
    } catch (Exception e) {
      return new Session(new LinkedHashMap<>());
    }
  }

  public boolean loggedIn() {
    return Boolean.TRUE.equals(values.get("logged_in"));
  }

  public Session signedIn(boolean value) {
    values.put("logged_in", value);
    return this;
  }

  public String locale() {
    Object locale = values.get("locale");
    return locale == null ? "" : String.valueOf(locale);
  }

  public Session withLocale(String locale) {
    if (locale == null || locale.isEmpty()) {
      values.remove("locale");
    } else {
      values.put("locale", locale);
    }
    return this;
  }

  /**
   * The link a shared watch was published at, shown once on the next page.
   *
   * <p>Not a message, because the page shows it with a control for copying it rather than in
   * the message list; it is cleared by the page that shows it, for the same reason a message is.
   */
  public String shareLink() {
    Object link = values.get("share-link");
    return link == null ? "" : String.valueOf(link);
  }

  public Session withShareLink(String link) {
    if (link == null || link.isEmpty()) {
      values.remove("share-link");
    } else {
      values.put("share-link", link);
    }
    return this;
  }

  /** One message and the class the page styles it with. */
  public record Flash(String message, String category) {}

  /** Adds a message for the next page this browser is shown. */
  public Session flash(String message, String category) {
    List<Object> flashes = flashList();
    List<Object> entry = new ArrayList<>();
    entry.add(category);
    entry.add(message);
    flashes.add(entry);
    values.put("_flashes", flashes);
    return this;
  }

  public Session flash(String message) {
    return flash(message, "message");
  }

  /** The waiting messages, which are cleared by being read -- that is what makes them once. */
  public List<Flash> takeFlashes() {
    List<Flash> out = new ArrayList<>();
    for (Object item : flashList()) {
      if (item instanceof List<?> pair && pair.size() == 2) {
        out.add(new Flash(String.valueOf(pair.get(1)), String.valueOf(pair.get(0))));
      }
    }
    values.remove("_flashes");
    return out;
  }

  public boolean hasFlashes() {
    return !flashList().isEmpty();
  }

  @SuppressWarnings("unchecked")
  private List<Object> flashList() {
    Object flashes = values.get("_flashes");
    if (flashes instanceof List<?> list) {
      return (List<Object>) list;
    }
    return new ArrayList<>();
  }

  /** The cookie to send back, or an expiring one when there is nothing left to carry. */
  public RawHeader cookie() {
    if (values.isEmpty()) {
      return RawHeader.create(
          "Set-Cookie",
          COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }
    String payload;
    try {
      payload = Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(values));
    } catch (Exception e) {
      payload = "";
    }
    String cookie = payload + "." + sign(payload);
    return RawHeader.create(
        "Set-Cookie",
        COOKIE_NAME + "=" + cookie + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax");
  }

  public HttpResponse attachTo(HttpResponse response) {
    return response.addHeader(cookie());
  }

  private static String sign(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(Site.secret(), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("cannot sign the session", e);
    }
  }
}
