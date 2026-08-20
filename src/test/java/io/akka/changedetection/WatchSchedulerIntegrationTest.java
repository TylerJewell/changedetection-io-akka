package io.akka.changedetection;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpServer;
import io.akka.changedetection.application.WatchEntity;
import io.akka.changedetection.application.WatchWorkflow;
import io.akka.changedetection.domain.Interval;
import io.akka.changedetection.domain.WatchConfig;
import io.akka.changedetection.domain.WatchState;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Question-log rows 1 and 2, and SPEC-001 §4 D4: a watch on its own clock, fetching a page over
 * a real socket, over a running runtime.
 *
 * <p>The page is served from a throwaway HTTP server in this test rather than mocked, so the
 * fetcher under test is the one the service ships.
 */
class WatchSchedulerIntegrationTest extends TestKitSupport {

  private HttpServer server;
  private final AtomicReference<String> pageBody = new AtomicReference<>("Price: 10");
  private final AtomicInteger fetches = new AtomicInteger();

  @BeforeEach
  void startPage() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/page",
        exchange -> {
          fetches.incrementAndGet();
          byte[] body = pageBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/plain");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void stopPage() {
    server.stop(0);
  }

  private String pageUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/page";
  }

  private WatchState status(String id) {
    return componentClient.forEventSourcedEntity(id).method(WatchEntity::status).invoke();
  }

  private void awaitAtLeast(String id, int snapshots) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      if (status(id).history().size() >= snapshots) {
        return;
      }
      sleep(100);
    }
    assertThat(status(id).history()).hasSizeGreaterThanOrEqualTo(snapshots);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void configureAndStart(String id, WatchConfig config) {
    componentClient.forEventSourcedEntity(id).method(WatchEntity::configure).invoke(config);
    componentClient.forWorkflow(id).method(WatchWorkflow::start).invoke(id);
  }

  @Test
  void aWatchKeepsFetchingOnItsOwnClockAndRecordsOnlyWhatChanged() {
    String id = "watch-clock";
    configureAndStart(
        id, WatchConfig.of(pageUrl()).withInterval(new Interval(0, 0, 0, 0, 3)));

    awaitAtLeast(id, 1);
    int fetchesAfterFirst = fetches.get();

    // The page has not moved, so however many more times it is fetched, nothing is recorded.
    sleep(7_000);
    assertThat(fetches.get()).isGreaterThan(fetchesAfterFirst);
    assertThat(status(id).history()).hasSize(1);

    pageBody.set("Price: 11");
    awaitAtLeast(id, 2);
    assertThat(status(id).history()).containsExactly("Price: 10", "Price: 11");
  }

  @Test
  void twoWatchesKeepSeparateIntervalsAndSeparateState() {
    configureAndStart(
        "watch-fast", WatchConfig.of(pageUrl()).withInterval(new Interval(0, 0, 0, 0, 3)));
    configureAndStart(
        "watch-slow", WatchConfig.of(pageUrl()).withInterval(new Interval(0, 0, 1, 0, 0)));

    awaitAtLeast("watch-fast", 1);
    awaitAtLeast("watch-slow", 1);

    long fastFirst = status("watch-fast").lastCheckedEpochSeconds();
    sleep(7_000);
    assertThat(status("watch-fast").lastCheckedEpochSeconds()).isGreaterThan(fastFirst);
    // The hourly watch has been left alone in the same window.
    assertThat(status("watch-slow").history()).hasSize(1);
  }

  @Test
  void anIgnoredLineChangingDoesNotRecordASnapshot() {
    String id = "watch-ignore";
    pageBody.set("Price: 10\nLast updated: 1");
    configureAndStart(
        id,
        WatchConfig.of(pageUrl())
            .withInterval(new Interval(0, 0, 0, 0, 3))
            .withIgnoreText(List.of("Last updated")));

    awaitAtLeast(id, 1);
    pageBody.set("Price: 10\nLast updated: 2");
    sleep(8_000);
    assertThat(status(id).history()).hasSize(1);

    pageBody.set("Price: 11\nLast updated: 3");
    awaitAtLeast(id, 2);
  }

  @Test
  void aPausedWatchIsFetchedButNeverChecked() {
    String id = "watch-paused";
    configureAndStart(
        id,
        WatchConfig.of(pageUrl()).withInterval(new Interval(0, 0, 0, 0, 3)).withPaused(true));
    sleep(8_000);
    assertThat(status(id).history()).isEmpty();
    assertThat(status(id).lastCheckedEpochSeconds()).isZero();
  }

  @Test
  void aChangedIntervalTakesEffectOnTheNextRun() {
    String id = "watch-interval";
    configureAndStart(
        id, WatchConfig.of(pageUrl()).withInterval(new Interval(0, 0, 1, 0, 0)));
    awaitAtLeast(id, 1);

    componentClient
        .forEventSourcedEntity(id)
        .method(WatchEntity::configure)
        .invoke(WatchConfig.of(pageUrl()).withInterval(new Interval(0, 0, 0, 0, 3)));
    componentClient.forWorkflow(id).method(WatchWorkflow::reschedule).invoke();

    long before = status(id).lastCheckedEpochSeconds();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline && status(id).lastCheckedEpochSeconds() == before) {
      sleep(100);
    }
    assertThat(status(id).lastCheckedEpochSeconds()).isGreaterThan(before);
  }
}
