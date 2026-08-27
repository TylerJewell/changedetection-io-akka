package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchEntity;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.model.Watch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is being checked and what is waiting to be.
 *
 * <p>The list's "queued: N" indicator is otherwise a number with nothing behind it. This page
 * is what turns it into an answer: which watches, in what order, and how long the one in front
 * has been running.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class QueueEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public QueueEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/queue")
  public HttpResponse queuePage() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/queue", "ui.ui_queue");
    HttpResponse refusal = Guard.requireSignIn(page, "/queue");
    if (refusal != null) {
      return refusal;
    }
    int perPage = perPage(store);
    int number = pageNumber(page);
    Map<String, Object> snapshot = snapshot(store, number, perPage);

    Map<String, String> carried = new LinkedHashMap<>();
    Pagination pagination =
        new Pagination(
            number,
            (int) asLong(snapshot.get("queued_count")),
            perPage,
            "/queue",
            carried,
            page.translate("queued items"),
            page.translate(
                "displaying <b>{start} - {end}</b> {record_name} in total <b>{total}</b>"));

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("snapshot", snapshot);
    variables.put("pagination", pagination);
    variables.put("extra_classes", "queue-full-width");
    variables.put("datastore", new DatastoreView(store));
    return page.session()
        .attachTo(Requests.html(Render.render(page, "queue.html", variables)));
  }

  @Get("/queue.json")
  public HttpResponse queueJson() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/queue.json", "ui.ui_queue");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    return Requests.json(snapshot(store, pageNumber(page), perPage(store)));
  }

  @Post("/queue/clear")
  public HttpResponse queueClear() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/queue/clear", "ui.ui_queue");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    int before = Site.queueSize();
    Site.clearQueue();
    page.session().flash("Queue cleared (" + before + " items removed).");
    return page.session().attachTo(Requests.redirect("/queue"));
  }

  /**
   * Abandons a check that is already running.
   *
   * <p>The watch is marked finished rather than left mid-check, because a watch stuck in the
   * checking state would never be picked up by the sweep again.
   */
  @Post("/queue/cancel-running")
  public HttpResponse cancelRunning(HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/queue/cancel-running", "ui.ui_queue");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    String uuid = submitted.first("uuid").strip();
    if (uuid.isEmpty()) {
      uuid = Requests.queryValue(requestContext(), "uuid", "").strip();
    }
    if (uuid.isEmpty()) {
      return Requests.json(
          StatusCodes.BAD_REQUEST, Map.of("ok", false, "error", "missing uuid"));
    }
    boolean running = false;
    for (var row : store.watchRows()) {
      if (row.uuid().equals(uuid) && row.checking()) {
        running = true;
        break;
      }
    }
    Site.unqueue(uuid);
    if (!running) {
      Map<String, Object> reply = new LinkedHashMap<>();
      reply.put("ok", false);
      reply.put("error", "uuid not currently running");
      reply.put("cancelled", false);
      return Requests.json(StatusCodes.NOT_FOUND, reply);
    }
    componentClient.forEventSourcedEntity(uuid).method(WatchEntity::finishCheck).invoke();
    Map<String, Object> reply = new LinkedHashMap<>();
    reply.put("ok", true);
    reply.put("cancelled", true);
    reply.put("uuid", uuid);
    return Requests.json(reply);
  }

  // ------------------------------------------------------------------ bits

  static int perPage(Store store) {
    Object configured = store.application().get("pager_size");
    int size = configured instanceof Number number ? number.intValue() : 50;
    return size <= 0 ? 50 : size;
  }

  static int pageNumber(Render.Page page) {
    String requested = WatchListFilters.first(page.query(), "page");
    if (requested.isEmpty()) {
      return 1;
    }
    try {
      return Math.max(1, Integer.parseInt(requested));
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  /** Everything the queue page shows, in the shape the page and its script both read. */
  static Map<String, Object> snapshot(Store store, int page, int perPage) {
    List<String> queued = new ArrayList<>(Site.queued());
    List<String> running = new ArrayList<>();
    for (var row : store.watchRows()) {
      if (row.checking()) {
        running.add(row.uuid());
        queued.remove(row.uuid());
      }
    }

    int offset = Math.max(0, (page - 1) * perPage);
    List<Object> queuedItems = new ArrayList<>();
    for (int index = offset; index < Math.min(queued.size(), offset + perPage); index++) {
      Map<String, Object> item = brief(store, queued.get(index));
      item.put("position", index);
      // Everything asked for by hand is immediate; the sweep asks for a check by starting it
      // rather than by queueing it, so nothing here ever carries a lower priority.
      item.put("priority", 1);
      item.put("priority_label", "immediate");
      item.put("enqueued_at", null);
      queuedItems.add(item);
    }

    List<Object> runningItems = new ArrayList<>();
    for (String uuid : running) {
      Map<String, Object> item = brief(store, uuid);
      item.put("started_at", null);
      runningItems.add(item);
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("immediate", queued.size());
    summary.put("clone", 0);
    summary.put("scheduled", 0);
    Map<String, Object> breakdown = new LinkedHashMap<>();
    if (!queued.isEmpty()) {
      breakdown.put("1", queued.size());
    }
    summary.put("priority_breakdown", breakdown);

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("worker_count", Render.workerCount(store));
    snapshot.put("running_count", runningItems.size());
    snapshot.put("queued_count", queued.size());
    snapshot.put("summary", summary);
    snapshot.put("running", runningItems);
    snapshot.put("queued", queuedItems);
    snapshot.put("page", page);
    snapshot.put("per_page", perPage);
    snapshot.put("total_pages", Math.max(1, (queued.size() + perPage - 1) / perPage));
    return snapshot;
  }

  /** Enough about a watch to recognise it, or a marker saying it is no longer there. */
  static Map<String, Object> brief(Store store, String uuid) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("uuid", uuid);
    WatchState state = store.watch(uuid);
    if (!state.exists()) {
      item.put("title", null);
      item.put("url", null);
      item.put("gone", true);
      return item;
    }
    Watch watch = state.asWatch();
    String title = watch.fields().string("title", "");
    item.put("title", title.isEmpty() ? null : title);
    item.put("url", watch.fields().string("url", ""));
    item.put("last_checked", watch.fields().longValue("last_checked", 0));
    Object error = watch.fields().get("last_error");
    item.put(
        "last_error",
        error == null || Boolean.FALSE.equals(error) || String.valueOf(error).isEmpty()
            ? null
            : error);
    item.put("paused", watch.fields().bool("paused"));
    item.put("gone", false);
    return item;
  }

  static long asLong(Object value) {
    return value instanceof Number number ? number.longValue() : 0;
  }
}
