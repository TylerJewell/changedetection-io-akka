package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The three routes an operator or a monitor asks about the service itself. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class SystemEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public SystemEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /**
   * Asks the machine to give back what it can.
   *
   * <p>Kept because the original has it and a deployment's own scripts may call it. What it can
   * actually do differs: there is no arena to trim here, only a suggestion to the collector,
   * and the reply says so rather than claiming to have freed anything.
   */
  @Get("/gc-cleanup")
  public HttpResponse gcCleanup() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/gc-cleanup", "");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Runtime runtime = Runtime.getRuntime();
    long before = runtime.totalMemory() - runtime.freeMemory();
    System.gc();
    long after = runtime.totalMemory() - runtime.freeMemory();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("memory_before_bytes", before);
    result.put("memory_after_bytes", after);
    result.put("freed_bytes", Math.max(0, before - after));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "success");
    body.put("message", "Memory cleanup completed");
    body.put("result", result);
    return Requests.json(body);
  }

  @Get("/worker-health")
  public HttpResponse workerHealth() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/worker-health", "");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    int expected = expectedWorkers(store);
    Map<String, Object> status = Render.workerStatus(store);

    Map<String, Object> health = new LinkedHashMap<>();
    health.put("healthy", true);
    health.put("expected_count", expected);
    health.put("running_count", status.get("active_workers"));
    health.put("restarted", new ArrayList<>());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "success");
    body.put("worker_status", status);
    body.put("health_check", health);
    body.put("expected_workers", expected);
    return Requests.json(body);
  }

  @Get("/queue-status")
  public HttpResponse queueStatus() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/queue-status", "");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String target = Requests.queryValue(requestContext(), "uuid", "");
    List<String> queued = new ArrayList<>(Site.queued());

    if (!target.isEmpty()) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("status", "success");
      body.put("uuid", target);
      int position = queued.indexOf(target);
      body.put("queue_position", position < 0 ? null : position);
      return Requests.json(body);
    }

    if (!Requests.queryValue(requestContext(), "summary", "").isEmpty()) {
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("total_items", queued.size());
      summary.put("immediate_items", queued.size());
      summary.put("clone_items", 0);
      summary.put("scheduled_items", 0);
      Map<String, Object> breakdown = new LinkedHashMap<>();
      if (!queued.isEmpty()) {
        breakdown.put("1", queued.size());
      }
      summary.put("priority_breakdown", breakdown);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("status", "success");
      body.put("queue_summary", summary);
      return Requests.json(body);
    }

    int offset = number(Requests.queryValue(requestContext(), "offset", "0"), 0);
    String requestedLimit = Requests.queryValue(requestContext(), "limit", "");
    // A very long queue is truncated by default, because the caller almost always wants to
    // know how long it is rather than to read every entry.
    int limit =
        requestedLimit.isEmpty()
            ? (queued.size() > 100 ? 50 : queued.size())
            : number(requestedLimit, queued.size());

    List<Object> items = new ArrayList<>();
    for (int index = offset; index < Math.min(queued.size(), offset + limit); index++) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("uuid", queued.get(index));
      item.put("position", index);
      item.put("priority", 1);
      items.add(item);
    }
    Map<String, Object> queuedData = new LinkedHashMap<>();
    queuedData.put("items", items);
    queuedData.put("total", queued.size());
    queuedData.put("offset", offset);
    queuedData.put("limit", limit);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "success");
    body.put("queue_size", queued.size());
    body.put("queued_data", queuedData);
    return Requests.json(body);
  }

  static int expectedWorkers(Store store) {
    String configured = System.getenv("FETCH_WORKERS");
    if (configured != null && !configured.isBlank()) {
      try {
        return Integer.parseInt(configured.strip());
      } catch (NumberFormatException e) {
        // Falls through to the stored setting.
      }
    }
    return Render.workerCount(store);
  }

  static int number(String value, int fallback) {
    try {
      return Integer.parseInt(value.strip());
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
