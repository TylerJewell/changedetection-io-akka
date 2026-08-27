package io.akka.changedetection.web;

import akka.NotUsed;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Pair;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.changedetection.application.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The connection the interface holds open, and the two things it sends back down it.
 *
 * <p>The original's pages ask the server for nothing on a timer; they hold a socket open and are
 * told. This keeps that shape and changes the transport: one stream out, and an ordinary request
 * for each of the two operations the page can start. The event names and payload shapes are the
 * original's, because the page's own script is what reads them.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class StreamEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public StreamEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/stream")
  public HttpResponse stream() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/stream", "");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    StreamHub.Subscriber subscriber = StreamHub.subscribe();

    // The first thing a reader is told is what is true now, so a page that reconnects after a
    // gap converges on current state rather than waiting for the next thing to change.
    subscriber.offer(
        new StreamHub.Event("queue_size", data(Map.of("q_length", Site.queueSize()))));
    subscriber.offer(
        new StreamHub.Event("general_stats_update", data(generalStats(store))));

    Source<StreamHub.Event, NotUsed> events =
        Source.unfoldAsync(
                subscriber,
                (StreamHub.Subscriber reader) ->
                    reader
                        .next()
                        .thenApply(
                            event ->
                                event == null
                                    ? Optional.<Pair<StreamHub.Subscriber, StreamHub.Event>>empty()
                                    : Optional.of(Pair.create(reader, event))))
            .watchTermination(
                (materialized, done) -> {
                  done.whenComplete((ignored, failure) -> StreamHub.unsubscribe(subscriber));
                  return NotUsed.getInstance();
                });

    return HttpResponses.serverSentEvents(
        events, StreamHub.Event::name, StreamHub.Event::data);
  }

  /** One button on one row: pause it, mute it, check it now. */
  @Post("/stream/watch-operation")
  public HttpResponse watchOperation(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(requestContext(), operations.store(), "/stream/watch-operation", "");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> json = Requests.json(body);
    String operation = String.valueOf(json.getOrDefault("op", ""));
    String uuid = String.valueOf(json.getOrDefault("uuid", ""));
    if (operation.isEmpty() || uuid.isEmpty()) {
      return Requests.json(
          akka.http.javadsl.model.StatusCodes.BAD_REQUEST,
          Map.of("success", false, "message", "No operation or watch given"));
    }
    Operations.Outcome outcome = operations.apply(operation, List.of(uuid), "");
    StreamHub.publish(
        "operation_result",
        Map.of("success", !"error".equals(outcome.type()), "message", outcome.message()));
    publishWatchUpdate(operations.store(), uuid);
    return Requests.json(Map.of("success", true, "message", outcome.message()));
  }

  /** The bar under the list: the same operation over everything ticked. */
  @Post("/stream/checkbox-operation")
  public HttpResponse checkboxOperation(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(requestContext(), operations.store(), "/stream/checkbox-operation", "");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> json = Requests.json(body);
    String operation = String.valueOf(json.getOrDefault("op", ""));
    List<String> uuids = new ArrayList<>();
    if (json.get("uuids") instanceof List<?> list) {
      for (Object item : list) {
        String value = String.valueOf(item).strip();
        if (!value.isEmpty()) {
          uuids.add(value);
        }
      }
    }
    Object extra = json.get("extra_data");
    Operations.Outcome outcome =
        operations.apply(operation, uuids, extra == null ? "" : String.valueOf(extra));
    StreamHub.publish(
        "operation_result",
        Map.of("success", !"error".equals(outcome.type()), "message", outcome.message()));
    if ("delete".equals(operation)) {
      for (String uuid : uuids) {
        StreamHub.publish("watch_deleted", Map.of("uuid", uuid));
      }
    } else {
      for (String uuid : uuids) {
        publishWatchUpdate(operations.store(), uuid);
      }
    }
    StreamHub.publish("general_stats_update", generalStats(operations.store()));
    return Requests.json(Map.of("success", true, "message", outcome.message()));
  }

  private void publishWatchUpdate(Store store, String uuid) {
    if (store.watch(uuid).exists()) {
      StreamHub.publish("watch_update", Map.of("watch", Map.of("uuid", uuid)));
    } else {
      StreamHub.publish("watch_deleted", Map.of("uuid", uuid));
    }
  }

  /** The counts the sidebar shows, which every change can move. */
  static Map<String, Object> generalStats(Store store) {
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("count_errors", errorCount(store));
    stats.put("has_unviewed", store.unreadChangesCount() > 0);
    stats.put("unread_changes_count", store.unreadChangesCount());
    stats.put("watch_count", store.watchUuids().size());
    return stats;
  }

  private static int errorCount(Store store) {
    int count = 0;
    for (io.akka.changedetection.application.WatchesView.WatchRow row : store.watchRows()) {
      if (row.lastError() != null && !row.lastError().isEmpty()) {
        count++;
      }
    }
    return count;
  }

  private static String data(Map<String, Object> payload) {
    Map<String, Object> body = new LinkedHashMap<>(payload);
    body.putIfAbsent("event_timestamp", System.currentTimeMillis() / 1000.0);
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    } catch (Exception e) {
      return "{}";
    }
  }
}
