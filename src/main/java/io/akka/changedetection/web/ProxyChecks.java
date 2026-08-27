package io.akka.changedetection.web;

import io.akka.changedetection.application.CheckRunner;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.fetchers.Fetcher;
import io.akka.changedetection.fetchers.Fetchers;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.ProcessorExceptions;
import io.akka.changedetection.processors.TextJsonDiffProcessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fetching one watch's page through every configured proxy at once, to see which work.
 *
 * <p>The page asks to start and then asks repeatedly what happened, so the work outlives the
 * request that began it. A check still running answers {@code RUNNING} rather than blocking,
 * which is what lets the page show a row per proxy filling in as each finishes.
 *
 * <p>What each outcome means is deliberately not "did it return 200": a page that answers 404
 * through a proxy proves the proxy works, and a filter that no longer matches proves it works
 * too. The failures worth reporting are the ones where nothing came back at all.
 */
final class ProxyChecks {

  /** Three at a time, because each holds a connection open for as long as the page takes. */
  private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

  private static final Map<String, Map<String, Future<Map<String, Object>>>> IN_PROGRESS =
      new ConcurrentHashMap<>();

  private ProxyChecks() {}

  /** What is known so far about this watch's checks, one entry per proxy. */
  static Map<String, Object> status(String watchUuid) {
    Map<String, Object> results = new LinkedHashMap<>();
    Map<String, Future<Map<String, Object>>> checks = IN_PROGRESS.get(watchUuid);
    if (checks == null) {
      return results;
    }
    for (Map.Entry<String, Future<Map<String, Object>>> entry : checks.entrySet()) {
      try {
        results.put(entry.getKey(), entry.getValue().get(50, TimeUnit.MILLISECONDS));
      } catch (Exception e) {
        results.put(entry.getKey(), Map.of("status", "RUNNING"));
      }
    }
    return results;
  }

  /** Starts a check per proxy, unless a set is already running for this watch. */
  static Map<String, Object> start(Store store, String watchUuid, List<String> proxies) {
    Map<String, Future<Map<String, Object>>> existing = IN_PROGRESS.get(watchUuid);
    if (existing != null) {
      Map<String, Object> current = status(watchUuid);
      for (Object value : current.values()) {
        if (value instanceof Map<?, ?> row && "RUNNING".equals(row.get("status"))) {
          return current;
        }
      }
    } else {
      IN_PROGRESS.put(watchUuid, new ConcurrentHashMap<>());
    }
    Map<String, Future<Map<String, Object>>> checks = IN_PROGRESS.get(watchUuid);
    for (String proxy : proxies) {
      checks.computeIfAbsent(proxy, key -> POOL.submit(() -> check(store, watchUuid, key)));
    }
    return status(watchUuid);
  }

  private static Map<String, Object> check(Store store, String watchUuid, String proxy) {
    long began = System.nanoTime();
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("status", "");
    status.put("length", 0);
    status.put("text", "");

    WatchState state = store.watch(watchUuid);
    if (!state.exists()) {
      status.put("status", "ERROR OTHER");
      status.put("text", "Error: no such watch");
      status.put("time", elapsed(began));
      return status;
    }
    Watch watch = state.asWatch();

    try {
      Fetcher fetcher =
          Fetchers.resolve(
              watch.fields().string("fetch_backend", ""),
              String.valueOf(store.application().getOrDefault("fetch_backend", "html_requests")),
              watch.isPdf());
      Fetcher.Request request = new Fetcher.Request();
      request.url = watch.link(text -> text);
      request.proxy = proxy;
      request.timeoutSeconds = 45;
      Fetched fetched = fetcher.fetch(request);
      new TextJsonDiffProcessor(new CheckRunner.ProcessorEnvironment(store))
          .run(watch, fetched, true);
      status.put("status", "OK");
      status.put("length", fetched.content == null ? 0 : fetched.content.length());
    } catch (ProcessorExceptions.NonSuccessStatus e) {
      int code = e.statusCode();
      if (code == 404) {
        status.put("status", "OK");
        status.put("text", "OK but 404 (page not found)");
      } else if (code == 403 || code == 401) {
        status.put("status", "ERROR");
        status.put("text", code + " - Access denied");
      } else {
        status.put("status", "ERROR");
        status.put("text", "Status code: " + code);
      }
    } catch (ProcessorExceptions.FilterNotFound e) {
      status.put("status", "OK");
      status.put("text", "OK but CSS/xPath filter not found (page changed layout?)");
    } catch (ProcessorExceptions.EmptyReply e) {
      int code = e.statusCode();
      status.put("status", "ERROR OTHER");
      status.put(
          "text",
          code == 403 || code == 401
              ? "Got empty reply with code " + code + " - Access denied"
              : "Empty reply with code " + code + ", needs chrome?");
    } catch (ProcessorExceptions.ReplyWithContentButNoText e) {
      status.put("status", "ERROR");
      status.put(
          "text",
          "Got reply but with no content - Status code " + e.statusCode()
              + " - It's possible that the filters were found, but contained no usable text (or"
              + " contained only an image).");
    } catch (RuntimeException e) {
      status.put("status", "ERROR OTHER");
      status.put("text", "Error: " + e.getClass().getSimpleName() + e.getMessage());
    }

    // Whatever came back is written into the page, so it is escaped here rather than trusted.
    status.put("text", escape(String.valueOf(status.get("text"))));
    status.put("time", elapsed(began));
    return status;
  }

  private static String elapsed(long began) {
    return String.format("%.2fs", (System.nanoTime() - began) / 1_000_000_000.0);
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&#34;")
        .replace("'", "&#39;");
  }
}
