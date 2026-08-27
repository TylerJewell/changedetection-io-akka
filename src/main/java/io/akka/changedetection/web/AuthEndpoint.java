package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.forms.SpecialFields;
import java.util.LinkedHashMap;
import java.util.Map;

/** Signing in, signing out, and choosing a language. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class AuthEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public AuthEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/login")
  public HttpResponse loginPage() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/login", "");
    String redirect = safeRedirect(Requests.queryValue(requestContext(), "redirect", ""));

    if (page.session().loggedIn()) {
      page.session().flash("Already logged in");
      return page.session().attachTo(Requests.redirect(redirect));
    }
    page.session().flash("You must be logged in, please log in.", "error");
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("redirect_url", redirect);
    String markup = Render.render(page, "login.html", variables);
    return page.session().attachTo(Requests.html(markup));
  }

  @Post("/login")
  public HttpResponse login(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/login", "");
    Requests.Submission submitted = Requests.submission(requestContext(), body);

    String requested = Requests.queryValue(requestContext(), "redirect", "");
    if (requested.isEmpty()) {
      requested = submitted.first("redirect");
    }
    String redirect = safeRedirect(requested);

    if (matches(submitted.first("password"), store.application())) {
      page.session().signedIn(true);
      return page.session().attachTo(Requests.redirect(redirect));
    }
    page.session().flash("Incorrect password", "error");
    Map<String, Object> arguments = new LinkedHashMap<>();
    if (!requested.isEmpty()) {
      arguments.put("redirect", requested);
    }
    return page.session().attachTo(Requests.redirect(Routes.build("login", arguments)));
  }

  @Get("/logout")
  public HttpResponse logout() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/logout", "");
    page.session().signedIn(false);

    String requested = Requests.queryValue(requestContext(), "redirect", "");
    if (!requested.isEmpty() && isSafe(requested)) {
      Map<String, Object> arguments = new LinkedHashMap<>();
      arguments.put("redirect", requested);
      return page.session().attachTo(Requests.redirect(Routes.build("login", arguments)));
    }
    return page.session().attachTo(Requests.redirect(Routes.build("login", Map.of())));
  }

  @Get("/set-language/{locale}")
  public HttpResponse setLanguage(String locale) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/set-language/" + locale, "");
    if (Translations.codes().contains(locale)) {
      page.session().withLocale(locale);
    }
    return page.session().attachTo(Requests.redirect(safeRedirect(
        Requests.queryValue(requestContext(), "redirect", ""))));
  }

  /**
   * Whether an address may be redirected to after signing in.
   *
   * <p>Only a path within this interface. A redirect the caller supplies is the classic way to
   * turn a sign-in page into a link that sends people somewhere else while looking like it
   * came from here, so anything with a scheme, a host, or a leading double slash is refused --
   * and the backslash is folded to a slash first, because some browsers read it that way and
   * the check has to see what the browser will see.
   */
  static boolean isSafe(String target) {
    if (target == null || target.isEmpty()) {
      return false;
    }
    String normalised = target.strip().replace('\\', '/');
    if (normalised.startsWith("//")) {
      return false;
    }
    if (!normalised.startsWith("/")) {
      return false;
    }
    java.net.URI parsed;
    try {
      parsed = java.net.URI.create(normalised);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (parsed.getScheme() != null || parsed.getAuthority() != null) {
      return false;
    }
    // A path this interface does not answer is refused too, so that a redirect cannot be used
    // to point at whatever the shipped-file route would serve.
    String path = parsed.getPath();
    return Routes.matchesKnownRoute(path);
  }

  static String safeRedirect(String requested) {
    return isSafe(requested) ? requested.strip().replace('\\', '/') : "/";
  }

  static boolean matches(String password, Map<String, Object> application) {
    String stored = System.getenv("SALTED_PASS");
    if (stored == null || stored.isBlank()) {
      Object configured = application.get("password");
      stored = configured == null ? "" : String.valueOf(configured);
    }
    if (stored.isEmpty() || stored.equals("false")) {
      return false;
    }
    return SpecialFields.SaltedPasswordField.matches(password == null ? "" : password, stored);
  }
}
