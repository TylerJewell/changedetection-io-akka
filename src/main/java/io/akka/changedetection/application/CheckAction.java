package io.akka.changedetection.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.changedetection.jinja.Environment;
import java.time.Duration;

/**
 * Where a check actually happens, and where the next sweep is armed.
 *
 * <p>A check is deliberately not started by whoever asked for it. The interface's "check now"
 * button, the API's recheck call, and the sweep that finds an overdue watch all put the same
 * request through here instead, so that a check outlives the request that asked for it and so
 * that a slow page cannot hold a caller waiting.
 */
@Component(id = "check")
public class CheckAction extends TimedAction {

  /** The gap between one sweep for overdue watches and the next. */
  static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);

  public static final String SWEEP_TIMER = "changedetection-sweep";

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public CheckAction(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  /** One watch, checked. */
  public Effect check(String uuid) {
    Store store = new Store(componentClient);
    Environment templates = TemplateEngine.notifications();
    new CheckRunner(store, new Notifier(store, templates)).run(uuid);
    return effects().done();
  }

  /**
   * One pass over every watch, starting the ones that are due, and arming the next pass.
   *
   * <p>The next pass is armed here rather than on a repeating schedule so that a pass which
   * takes longer than the interval does not overlap itself: a hundred watches all coming due
   * together would otherwise start a hundred sweeps.
   */
  public Effect sweep(String ignored) {
    try {
      Store store = new Store(componentClient);
      for (Scheduler.Decision decision :
          Scheduler.decide(store, store.watchRows(), java.time.ZonedDateTime.now())) {
        if (!decision.due()) {
          continue;
        }
        if (decision.jitter() != 0) {
          componentClient
              .forEventSourcedEntity(decision.uuid())
              .method(WatchEntity::drawJitter)
              .invoke(new WatchEntity.DrawJitter(decision.jitter()));
        }
        start(decision.uuid());
      }
    } finally {
      armSweep();
    }
    return effects().done();
  }

  /** Asks for a check to run, without waiting for it. */
  public void start(String uuid) {
    timers.createSingleTimer(
        "check-" + uuid,
        Duration.ZERO,
        componentClient.forTimedAction().method(CheckAction::check).deferred(uuid));
  }

  public void armSweep() {
    timers.createSingleTimer(
        SWEEP_TIMER,
        SWEEP_INTERVAL,
        componentClient.forTimedAction().method(CheckAction::sweep).deferred("sweep"));
  }
}
