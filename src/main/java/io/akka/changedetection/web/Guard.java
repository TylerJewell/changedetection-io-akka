package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpResponse;
import io.akka.changedetection.model.Fields;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who may see what.
 *
 * <p>The whole interface is open until a password is set; setting one closes everything except
 * signing in, the shipped files, and choosing a language -- the last two because the sign-in
 * page itself needs them. One further exception is deliberate and switched on separately: the
 * read-only difference pages can be left open so a change can be shared with someone who has no
 * account, and it covers exactly three routes rather than a prefix, because a prefix would also
 * have let through the ones that change something.
 */
public final class Guard {

  private Guard() {}


  /**
   * Refuses the request when a password is set and nobody has signed in.
   *
   * @return the reply to send instead, or null when the request may proceed
   */
  public static HttpResponse requireSignIn(Render.Page page, String requestedPath) {
    if (!Render.hasPassword(page.store().application()) || page.session().loggedIn()) {
      return null;
    }
    page.session().flash("You must be logged in, please log in.", "error");
    Map<String, Object> arguments = new LinkedHashMap<>();
    if (requestedPath != null && !requestedPath.isEmpty() && !requestedPath.equals("/")) {
      arguments.put("redirect", requestedPath);
    }
    return page.session().attachTo(Requests.redirect(Routes.build("login", arguments)));
  }

  /**
   * The same, except where the operator has opened the difference pages to everyone.
   *
   * <p>Only for the three routes that show a change and nothing else; the ones under the same
   * path that extract or export are changes of state and stay closed.
   */
  public static HttpResponse requireSignInUnlessShared(Render.Page page, String requestedPath) {
    Map<String, Object> application = page.store().application();
    if (Fields.truthy(application.get("shared_diff_access"))) {
      return null;
    }
    return requireSignIn(page, requestedPath);
  }

  /** Whether the caller presented the key the API is protected with. */
  public static boolean apiKeyAccepted(String presented, Map<String, Object> application) {
    if (!Fields.truthy(application.getOrDefault("api_access_token_enabled", true))) {
      return true;
    }
    Object stored = application.get("api_access_token");
    if (stored == null || String.valueOf(stored).isEmpty()) {
      return false;
    }
    if (presented == null || presented.isEmpty()) {
      return false;
    }
    return java.security.MessageDigest.isEqual(
        String.valueOf(stored).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Whether a feed request carried the token that feed readers are given. */
  public static boolean feedTokenAccepted(String presented, Map<String, Object> application) {
    Object stored = application.get("rss_access_token");
    if (stored == null || String.valueOf(stored).isEmpty()) {
      return true;
    }
    return presented != null
        && java.security.MessageDigest.isEqual(
            String.valueOf(stored).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
