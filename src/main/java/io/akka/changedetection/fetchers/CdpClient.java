package io.akka.changedetection.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * A conversation with a running browser, over the protocol the browser itself speaks.
 *
 * <p>The original's two browser-driven fetchers both end up here: one connects through a
 * library that speaks this protocol and the other through a different library that speaks the
 * same one. Talking to it directly is fewer moving parts than either, and it is the protocol
 * that is the contract -- the libraries are just two ways of writing it down.
 *
 * <p>Replies arrive out of order and interleaved with events, so every request carries an
 * identifier and waits for its own reply. A reply that never comes is a timeout rather than a
 * hang, because a browser that stops answering must not stop the whole check queue.
 */
public final class CdpClient implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WebSocket socket;
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final List<BiConsumer<String, JsonNode>> eventListeners = new CopyOnWriteArrayList<>();
  private final StringBuilder partial = new StringBuilder();
  private final long timeoutMillis;

  private CdpClient(WebSocket socket, long timeoutMillis) {
    this.socket = socket;
    this.timeoutMillis = timeoutMillis;
  }

  public static CdpClient connect(String websocketUrl, long timeoutMillis) {
    CdpClient[] holder = new CdpClient[1];
    try {
      WebSocket socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .connectTimeout(Duration.ofMillis(timeoutMillis))
              .buildAsync(URI.create(websocketUrl), new Adapter(holder))
              .get(timeoutMillis, TimeUnit.MILLISECONDS);
      holder[0] = new CdpClient(socket, timeoutMillis);
      socket.request(1);
      return holder[0];
    } catch (Exception e) {
      throw new io.akka.changedetection.processors.ProcessorExceptions.BrowserConnectError(
          "Error connecting to the browser at " + websocketUrl + " - " + e.getMessage());
    }
  }

  /** The listener, kept separate so the client can be handed to it after it is built. */
  private static final class Adapter implements WebSocket.Listener {
    private final CdpClient[] holder;

    Adapter(CdpClient[] holder) {
      this.holder = holder;
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onText(
        WebSocket webSocket, CharSequence data, boolean last) {
      if (holder[0] != null) {
        holder[0].onText(data, last);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      if (holder[0] != null) {
        holder[0].failAll(error);
      }
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(
        WebSocket webSocket, int statusCode, String reason) {
      if (holder[0] != null) {
        holder[0].failAll(new IllegalStateException("browser closed the connection: " + reason));
      }
      return null;
    }
  }

  private synchronized void onText(CharSequence data, boolean last) {
    partial.append(data);
    if (!last) {
      return;
    }
    String message = partial.toString();
    partial.setLength(0);
    try {
      JsonNode node = MAPPER.readTree(message);
      if (node.has("id")) {
        CompletableFuture<JsonNode> future = pending.remove(node.get("id").asInt());
        if (future != null) {
          if (node.has("error")) {
            future.completeExceptionally(
                new IllegalStateException(node.get("error").toString()));
          } else {
            future.complete(node.path("result"));
          }
        }
      } else if (node.has("method")) {
        for (BiConsumer<String, JsonNode> listener : eventListeners) {
          listener.accept(node.get("method").asText(), node.path("params"));
        }
      }
    } catch (Exception e) {
      // A message that will not parse is not a reply to anything, so nothing is waiting on it.
    }
  }

  private void failAll(Throwable error) {
    for (CompletableFuture<JsonNode> future : pending.values()) {
      future.completeExceptionally(error);
    }
    pending.clear();
  }

  public void onEvent(BiConsumer<String, JsonNode> listener) {
    eventListeners.add(listener);
  }

  public JsonNode send(String method, Map<String, Object> params) {
    return send(method, params, null);
  }

  /** One command, addressed to the whole browser or to one attached page. */
  public JsonNode send(String method, Map<String, Object> params, String sessionId) {
    int id = nextId.getAndIncrement();
    ObjectNode message = MAPPER.createObjectNode();
    message.put("id", id);
    message.put("method", method);
    if (params != null && !params.isEmpty()) {
      message.set("params", MAPPER.valueToTree(params));
    }
    if (sessionId != null) {
      message.put("sessionId", sessionId);
    }
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);
    try {
      socket.sendText(MAPPER.writeValueAsString(message), true).join();
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      pending.remove(id);
      throw new io.akka.changedetection.processors.ProcessorExceptions.BrowserFetchTimedOut(
          "The browser did not answer " + method + " in time");
    } catch (Exception e) {
      pending.remove(id);
      throw new io.akka.changedetection.processors.ProcessorExceptions.BrowserConnectError(
          method + " failed: " + e.getMessage());
    }
  }

  @Override
  public void close() {
    try {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    } catch (RuntimeException e) {
      socket.abort();
    }
  }
}
