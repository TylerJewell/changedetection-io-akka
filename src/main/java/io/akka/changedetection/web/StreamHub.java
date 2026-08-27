package io.akka.changedetection.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Everything the interface is told without having asked.
 *
 * <p>The original pushes these over a socket; here they go out as server-sent events, which is
 * the same direction of travel over a plainer transport. What matters for the interface is that
 * the names and the shapes are the original's, because the page's own script reads them.
 *
 * <p>A reader that goes away takes its place with it: the stream completing removes the
 * subscriber, and a subscriber whose backlog fills is dropped rather than held, because a
 * reader that cannot keep up is one that has already stopped reading.
 */
public final class StreamHub {

  /** How much a slow reader may fall behind before it is given up on. */
  private static final int BACKLOG_LIMIT = 256;

  /**
   * How often a comment is sent when nothing else is.
   *
   * <p>Without one, neither end learns the connection has gone until something is sent, which
   * on a quiet installation can be hours.
   */
  private static final long KEEPALIVE_SECONDS = 20;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<Subscriber> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

  private static final ScheduledExecutorService CLOCK =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "changedetection-stream-keepalive");
            thread.setDaemon(true);
            return thread;
          });

  static {
    CLOCK.scheduleAtFixedRate(
        () -> publish("keepalive", Map.of()),
        KEEPALIVE_SECONDS,
        KEEPALIVE_SECONDS,
        TimeUnit.SECONDS);
  }

  private StreamHub() {}

  /**
   * One thing that happened, named the way the interface's own script names it.
   *
   * <p>The payload carries the name under {@code event} as well, because what a reader is
   * sent as the event's type is derived from the payload rather than passed beside it. The
   * interface's own handlers read the fields they know and ignore the rest, so the extra key
   * costs nothing and is what makes the type arrive at all.
   */
  public record Event(String name, com.fasterxml.jackson.databind.node.ObjectNode payload) {}

  /** One reader, and what it has not been given yet. */
  public static final class Subscriber {
    private final Queue<Event> pending = new ArrayDeque<>();
    private CompletableFuture<Event> waiting;
    private volatile boolean closed;

    public synchronized void offer(Event event) {
      if (closed) {
        return;
      }
      if (waiting != null) {
        CompletableFuture<Event> waiter = waiting;
        waiting = null;
        waiter.complete(event);
        return;
      }
      if (pending.size() >= BACKLOG_LIMIT) {
        close();
        return;
      }
      pending.add(event);
    }

    /** The next thing to send, whenever there is one. */
    public synchronized CompletionStage<Event> next() {
      if (closed) {
        return CompletableFuture.completedFuture(null);
      }
      Event ready = pending.poll();
      if (ready != null) {
        return CompletableFuture.completedFuture(ready);
      }
      waiting = new CompletableFuture<>();
      return waiting;
    }

    synchronized void close() {
      closed = true;
      if (waiting != null) {
        waiting.complete(null);
        waiting = null;
      }
      pending.clear();
    }
  }

  public static Subscriber subscribe() {
    Subscriber subscriber = new Subscriber();
    SUBSCRIBERS.add(subscriber);
    return subscriber;
  }

  public static void unsubscribe(Subscriber subscriber) {
    SUBSCRIBERS.remove(subscriber);
    subscriber.close();
  }

  /** How many readers are attached, which the queue page reports. */
  public static int subscriberCount() {
    return SUBSCRIBERS.size();
  }

  /** Tells every reader, dropping any that has stopped reading. */
  public static void publish(String name, Object payload) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (payload instanceof Map<?, ?> map) {
      map.forEach((key, value) -> body.put(String.valueOf(key), value));
    } else {
      body.put("value", payload);
    }
    // Every event the interface reads carries the moment it happened, and the page prints it.
    body.putIfAbsent("event_timestamp", System.currentTimeMillis() / 1000.0);

    body.put("event", name);
    com.fasterxml.jackson.databind.node.ObjectNode node;
    try {
      node = MAPPER.valueToTree(body);
    } catch (Exception e) {
      node = MAPPER.createObjectNode();
      node.put("event", name);
    }
    Event event = new Event(name, node);
    Collection<Subscriber> everyone = SUBSCRIBERS;
    for (Subscriber subscriber : everyone) {
      subscriber.offer(event);
      if (subscriber.closed) {
        SUBSCRIBERS.remove(subscriber);
      }
    }
  }
}
