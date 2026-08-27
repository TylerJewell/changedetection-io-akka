package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.text.HtmlTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The newest stored version of a watch, as it was compared.
 *
 * <p>Shown with the lines a rule would act on picked out -- the ones that trigger a change, the
 * ones ignored when comparing, and the ones that block a change entirely. That is the only way
 * to tell whether a rule does what its author meant, short of waiting for a change.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class PreviewEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public PreviewEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/preview/{uuid}")
  public HttpResponse previewPage(String uuid) {
    return preview(uuid, null);
  }

  @Post("/preview/{uuid}")
  public HttpResponse previewSubmit(String uuid, HttpEntity.Strict body) {
    return preview(uuid, Requests.submission(requestContext(), body));
  }

  private HttpResponse preview(String uuid, Requests.Submission submitted) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/preview/" + uuid, "ui.ui_preview");
    HttpResponse refusal = Guard.requireSignIn(page, "/preview/" + uuid);
    if (refusal != null) {
      return refusal;
    }
    String resolved = EditEndpoint.resolve(store, uuid);
    WatchState state = store.watch(resolved);
    if (!state.exists()) {
      page.session().flash("No history found for the specified link, bad link?", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    Watch watch = state.asWatch();
    Environment environment = Render.environmentFor(page, store.application());
    boolean hasProxies = new DatastoreView(store).proxies() != null;
    WatchView view =
        new WatchView(
            watch,
            environment,
            false,
            null,
            hasProxies,
            store.sideStore(resolved, "favicon-name"));

    List<Long> versions = watch.history();
    String requested =
        submitted == null
            ? Requests.queryValue(requestContext(), "version", "")
            : submitted.first("version");

    if (watch.fields().string("processor", "").equals("image_ssim_diff")) {
      if (versions.isEmpty()) {
        page.session().flash("Preview unavailable - No snapshots captured yet", "error");
        return page.session().attachTo(Requests.redirect("/"));
      }
      long timestamp = chosen(versions, requested);
      Map<String, Object> variables = new LinkedHashMap<>();
      variables.put("watch", view);
      variables.put("datastore", new DatastoreView(store));
      variables.put("uuid", resolved);
      variables.put("versions", versions);
      variables.put("timestamp", timestamp);
      variables.put("current_diff_url", watch.fields().string("url", ""));
      return page.session()
          .attachTo(
              Requests.html(
                  Render.renderWith(
                      page, environment, "image_ssim_diff/preview.html", variables)));
    }

    Object content = new ArrayList<>();
    Long timestamp = null;
    List<Integer> triggered = new ArrayList<>();
    List<Integer> ignored = new ArrayList<>();
    List<Integer> blocked = new ArrayList<>();

    String errorText = store.sideStore(resolved, "last-error.txt");
    boolean hasErrorEvidence =
        (errorText != null && !errorText.isEmpty())
            || !Boolean.FALSE.equals(DiffEndpoint.errorScreenshot(store, resolved));
    if (versions.isEmpty() && hasErrorEvidence) {
      page.session()
          .flash("Preview unavailable - No fetch/check completed or triggers not reached", "error");
    } else if (!versions.isEmpty()) {
      timestamp = chosen(versions, requested);
      String stored = store.snapshot(resolved, timestamp);
      if (stored == null) {
        List<Object> lines = new ArrayList<>();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("line", "File doesnt exist or unable to read timestamp " + timestamp);
        line.put("classes", "");
        lines.add(line);
        content = lines;
      } else {
        content = stored;
        triggered = HtmlTools.ignoredLineNumbers(stored, watch.fields().strings("trigger_text"));
        ignored = HtmlTools.ignoredLineNumbers(stored, watch.fields().strings("ignore_text"));
        blocked =
            HtmlTools.ignoredLineNumbers(
                stored, watch.fields().strings("text_should_not_be_present"));
      }
    }

    var fetcher =
        io.akka.changedetection.fetchers.Fetchers.resolve(
            watch.fields().string("fetch_backend", "system"),
            String.valueOf(store.application().getOrDefault("fetch_backend", "html_requests")),
            false);
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put("supports_browser_steps", fetcher != null && fetcher.supportsBrowserSteps());
    capabilities.put("supports_screenshots", fetcher != null && fetcher.supportsScreenshots());
    capabilities.put(
        "supports_xpath_element_data", fetcher != null && fetcher.supportsElementPositions());

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("capabilities", capabilities);
    variables.put("content", content);
    variables.put("current_diff_url", watch.fields().string("url", ""));
    variables.put("current_version", timestamp);
    variables.put(
        "extra_stylesheets",
        List.of(
            Routes.build("static_content", Map.of("group", "styles", "filename", "diff.css"))));
    variables.put(
        "extra_title",
        " - " + page.translate("Diff") + " - " + view.label() + " @ " + timestamp);
    variables.put("highlight_ignored_line_numbers", ignored);
    variables.put("highlight_triggered_line_numbers", triggered);
    variables.put("highlight_blocked_line_numbers", blocked);
    variables.put("history_n", versions.size());
    variables.put("is_html_webdriver", fetcher != null && fetcher.supportsScreenshots());
    variables.put("last_error", watch.fields().get("last_error"));
    variables.put("last_error_screenshot", DiffEndpoint.errorScreenshot(store, resolved));
    variables.put("last_error_text", errorText);
    variables.put("screenshot", DiffEndpoint.screenshot(store, resolved));
    variables.put("uuid", resolved);
    variables.put("versions", versions);
    variables.put("watch", view);
    variables.put("datastore", new DatastoreView(store));

    return page.session()
        .attachTo(
            Requests.html(Render.renderWith(page, environment, "preview.html", variables)));
  }

  /** The stored picture for one version, served on its own rather than built into the page. */
  @Get("/preview/{uuid}/processor-asset/{assetName}")
  public HttpResponse processorAsset(String uuid, String assetName) {
    Store store = new Store(componentClient);
    String path = "/preview/" + uuid + "/processor-asset/" + assetName;
    Render.Page page = Render.page(requestContext(), store, path, "ui.ui_preview");
    HttpResponse refusal = Guard.requireSignIn(page, path);
    if (refusal != null) {
      return refusal;
    }
    if (!assetName.equals("screenshot")) {
      return Requests.notFound();
    }
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      return Requests.notFound();
    }
    List<Long> versions = state.asWatch().history();
    if (versions.isEmpty()) {
      return Requests.notFound();
    }
    long timestamp = chosen(versions, Requests.queryValue(requestContext(), "version", ""));
    byte[] body = DiffEndpoint.decode(store.snapshot(uuid, timestamp));
    if (body.length == 0) {
      return Requests.notFound();
    }
    return Requests.bytes(StatusCodes.OK, DiffEndpoint.pictureType(body), body)
        .addHeader(RawHeader.create("Cache-Control", "public, max-age=3600"));
  }

  static long chosen(List<Long> versions, String requested) {
    long newest = versions.get(versions.size() - 1);
    if (requested == null || requested.isEmpty()) {
      return newest;
    }
    for (Long version : versions) {
      if (String.valueOf(version).equals(requested)) {
        return version;
      }
    }
    return newest;
  }
}
