package io.akka.changedetection.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.changedetection.domain.Schedule;
import io.akka.changedetection.domain.Verdict;
import io.akka.changedetection.domain.WatchState;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The clock for one watch. SPEC-001 §3 R2-R7 and §4 D4.
 *
 * <p>Each watch arms its own next run rather than being swept up by one loop over all watches.
 * The rule about when a check may run is the same either way; what differs is that a watch whose
 * interval changes must have its timer re-armed, which {@link #reschedule} does.
 */
@Component(id = "watch-scheduler")
public class WatchWorkflow extends Workflow<WatchWorkflow.Scheduled> {

  /**
   * @param missedIntervals how many due moments passed unserved since the last run. Zero in
   *     ordinary operation; non-zero after the service was down. SPEC-001 §4 D2 — one catch-up
   *     check runs, not the backlog, and this is how many were dropped.
   */
  public record Scheduled(String watchId, long missedIntervals, long lastVerdictAtEpochSeconds) {}

  private static final Logger logger = LoggerFactory.getLogger(WatchWorkflow.class);

  private final ComponentClient client;
  private final Fetcher fetcher = new Fetcher();

  public WatchWorkflow(ComponentClient client) {
    this.client = client;
  }

  @Override
  public WorkflowSettings settings() {
    // The check step fetches over the network, and the fetcher gives a request 30 seconds. The
    // default step timeout is shorter than that, so a slow page would time the step out while
    // the request it is waiting for is still legitimately in flight.
    return WorkflowSettings.builder()
        .stepTimeout(WatchWorkflow::check, Duration.ofSeconds(60))
        .build();
  }

  public Effect<Done> start(String watchId) {
    return effects()
        .updateState(new Scheduled(watchId, 0, 0))
        .transitionTo(WatchWorkflow::check)
        .thenReply(Done.getInstance());
  }

  /** Re-arms after a configuration change, so a new interval takes effect without a restart. */
  public Effect<Done> reschedule() {
    return effects().transitionTo(WatchWorkflow::check).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Scheduled> state() {
    return effects().reply(currentState());
  }

  public StepEffect check() {
    var scheduled = currentState();
    WatchState watch =
        client.forEventSourcedEntity(scheduled.watchId()).method(WatchEntity::status).invoke();

    if (!watch.isConfigured()) {
      return stepEffects().thenEnd();
    }

    Instant now = Instant.now();
    long nowSeconds = now.getEpochSecond();
    Duration interval = watch.config().interval().toDuration();

    boolean allowed =
        Schedule.allowsCheck(watch.config().window(), now)
            && Schedule.isDue(
                nowSeconds,
                watch.lastCheckedEpochSeconds(),
                interval,
                watch.jitterSeconds(),
                watch.config().paused());

    long missed = missedIntervals(nowSeconds, watch.lastCheckedEpochSeconds(), interval);

    if (allowed) {
      var fetched = fetcher.fetch(watch.config().url());
      // A body the entity would refuse is dropped here instead of offered. Offering it would
      // fail the step, and a failed step is retried, so an oversized page would be fetched on
      // a retry loop rather than once per interval.
      if (fetched.body().length() <= WatchEntity.MAX_BODY_CHARS) {
        client
            .forEventSourcedEntity(scheduled.watchId())
            .method(WatchEntity::submit)
            .invoke(new WatchEntity.Submission(fetched.body(), fetched.contentType(), nowSeconds));
      } else {
        logger.warn(
            "watch {} fetched {} characters, over the {} the watch will judge; check skipped",
            scheduled.watchId(),
            fetched.body().length(),
            WatchEntity.MAX_BODY_CHARS);
      }
      missed = 0;
    }

    return stepEffects()
        .updateState(new Scheduled(scheduled.watchId(), missed, nowSeconds))
        .thenTransitionTo(WatchWorkflow::sleep)
        .withInput(nextDelay(interval, watch.jitterSeconds(), allowed));
  }

  public StepEffect sleep(Duration delay) {
    timers()
        .createSingleTimer(
            "recheck-" + currentState().watchId(),
            delay,
            client.forWorkflow(commandContext().workflowId()).method(WatchWorkflow::reschedule).deferred());
    return stepEffects().thenPause();
  }

  /**
   * SPEC-001 §4 D2: how many due moments went unserved. Reported rather than replayed — forty-nine
   * fetches of a page can only tell you what it looks like now.
   */
  static long missedIntervals(long nowSeconds, long lastCheckedSeconds, Duration interval) {
    if (interval.isZero() || lastCheckedSeconds == 0) {
      return 0;
    }
    long elapsed = nowSeconds - lastCheckedSeconds;
    return Math.max(0, elapsed / interval.getSeconds() - 1);
  }

  private static Duration nextDelay(Duration interval, long jitterSeconds, boolean ranACheck) {
    long seconds = ranACheck ? interval.getSeconds() + jitterSeconds : interval.getSeconds();
    return Duration.ofSeconds(Math.max(Schedule.FLOOR_SECONDS, seconds));
  }
}
