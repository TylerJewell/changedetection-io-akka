package io.akka.changedetection;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.changedetection.application.CheckAction;
import io.akka.changedetection.application.SettingsEntity;
import io.akka.changedetection.cli.Options;
import io.akka.changedetection.web.Site;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What has to be true before the first request arrives.
 *
 * <p>Two things: the settings have to exist, because everything else reads them and a missing
 * settings record would make the first page fail rather than show defaults; and the sweep that
 * finds overdue watches has to be running, because nothing else ever starts it.
 */
@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public Bootstrap(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  @Override
  public void onStartup() {
    Options.applyFromEnvironment();
    log.info("changedetection.io {} starting, data at {}", Site.VERSION, Site.datastorePath());

    // Reading the settings is what creates them, and doing it here means the first request
    // does not pay for it -- nor fail if two arrive at once.
    componentClient.forKeyValueEntity(SettingsEntity.ID).method(SettingsEntity::read).invoke();

    if (Options.batchMode()) {
      log.info("running without the periodic sweep");
      return;
    }
    timers.createSingleTimer(
        CheckAction.SWEEP_TIMER,
        Duration.ofSeconds(1),
        componentClient.forTimedAction().method(CheckAction::sweep).deferred("sweep"));
    log.info("sweep armed, first pass in {}", Duration.ofSeconds(1));
  }
}
