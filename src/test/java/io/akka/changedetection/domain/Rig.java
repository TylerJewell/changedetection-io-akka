package io.akka.changedetection.domain;

import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.CheckOutcome;
import io.akka.changedetection.processors.Fetched;
import io.akka.changedetection.processors.TextJsonDiffProcessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the real decision procedure with everything around it stood in for.
 *
 * <p>What is replaced is the network and the store, both of which a claim here is never about.
 * The processor, the filters, the rules and the checksum are the rebuild's own classes.
 *
 * <p>The store stands in as a set of maps rather than as a recording object, so a sequence of
 * checks sees what the one before it wrote -- which is the only way the rules that depend on
 * what was already seen can be exercised at all.
 */
final class Rig implements TextJsonDiffProcessor.Environment {

  private final Map<String, String> rawChecksums = new LinkedHashMap<>();
  private final Map<String, String> lastFetched = new LinkedHashMap<>();
  private final Map<String, Map<Long, String>> snapshots = new LinkedHashMap<>();
  private final Map<String, Object> application;

  private boolean conditionsPass = true;

  @SuppressWarnings("unchecked")
  Rig() {
    Map<String, Object> settings = AppSettings.create();
    Map<String, Object> tree = (Map<String, Object>) settings.get("settings");
    this.application = (Map<String, Object>) tree.get("application");
  }

  @Override
  public Map<String, Object> application() {
    return application;
  }

  void conditionsPass(boolean value) {
    this.conditionsPass = value;
  }

  @Override
  public List<String> tagOverrides(String watchUuid, String attribute) {
    return new ArrayList<>();
  }

  @Override
  public String lastRawContentChecksum(String watchUuid) {
    String value = rawChecksums.get(watchUuid);
    return value == null || value.isEmpty() ? null : value;
  }

  @Override
  public void updateLastRawContentChecksum(String watchUuid, String checksum) {
    rawChecksums.put(watchUuid, checksum);
  }

  @Override
  public String snapshot(String watchUuid, long timestamp) {
    return snapshots.getOrDefault(watchUuid, Map.of()).get(timestamp);
  }

  @Override
  public String lastFetchedTextBeforeFilters(String watchUuid) {
    return lastFetched.get(watchUuid);
  }

  @Override
  public void saveLastFetchedTextBeforeFilters(String watchUuid, String text) {
    lastFetched.put(watchUuid, text);
  }

  @Override
  public boolean conditionsAllow(Watch watch, String text) {
    return conditionsPass;
  }

  @Override
  public String pdfToHtml(byte[] rawContent) {
    return null;
  }

  /** One check, with what it decided written back the way a real check would write it. */
  Result check(Watch watch, String body) {
    return check(watch, body, "text/html");
  }

  Result check(Watch watch, String body, String contentType) {
    Fetched fetched = new Fetched(body, contentType, 200);
    CheckOutcome outcome;
    String error = null;
    try {
      outcome = new TextJsonDiffProcessor(this).run(watch, fetched);
    } catch (RuntimeException e) {
      return new Result(false, e.getClass().getSimpleName(), null);
    }
    if (outcome.changed()) {
      long stamp = System.currentTimeMillis() / 1000 + snapshots
          .getOrDefault(watch.uuid(), Map.of()).size();
      snapshots
          .computeIfAbsent(watch.uuid(), key -> new LinkedHashMap<>())
          .put(stamp, outcome.contents());
      List<Long> history = new ArrayList<>(snapshots.get(watch.uuid()).keySet());
      watch.setHistory(history);
    }
    watch.updateSystem(outcome.updates());
    watch.resetEditedFlag();
    return new Result(outcome.changed(), error, outcome.contents());
  }

  /** What one check answered. */
  record Result(boolean changed, String error, String contents) {}
}
