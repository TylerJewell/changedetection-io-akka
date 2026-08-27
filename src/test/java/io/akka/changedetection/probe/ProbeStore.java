package io.akka.changedetection.probe;

import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.processors.TextJsonDiffProcessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A store held in memory, so a probe can drive a sequence of checks against one watch.
 *
 * <p>Standing in for the store is fair -- it is a place to put things, not the thing under
 * test -- and the subject of every question the probe asks, the decision procedure, is the
 * real one.
 */
final class ProbeStore implements TextJsonDiffProcessor.Environment {

  final Map<String, Object> data = AppSettings.create();
  final Map<String, Watch> watches = new LinkedHashMap<>();
  final Map<String, Map<Long, String>> snapshots = new LinkedHashMap<>();
  final Map<String, String> rawChecksums = new LinkedHashMap<>();
  final Map<String, String> textBeforeFilters = new LinkedHashMap<>();
  final Map<String, Map<String, List<String>>> tagOverrides = new LinkedHashMap<>();

  @SuppressWarnings("unchecked")
  Map<String, Object> applicationSettings() {
    return (Map<String, Object>)
        ((Map<String, Object>) data.get("settings")).get("application");
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> requestSettings() {
    return (Map<String, Object>) ((Map<String, Object>) data.get("settings")).get("requests");
  }

  public Watch addWatch(String uuid, Map<String, Object> fields) {
    Watch watch = Watch.create(uuid);
    watch.fields().putAll(fields);
    watch.resetEditedFlag();
    watches.put(uuid, watch);
    snapshots.put(uuid, new LinkedHashMap<>());
    return watch;
  }

  void saveSnapshot(String uuid, long timestamp, String contents) {
    snapshots.get(uuid).put(timestamp, contents);
    List<Long> history = new ArrayList<>(snapshots.get(uuid).keySet());
    java.util.Collections.sort(history);
    watches.get(uuid).setHistory(history);
  }

  @Override
  public List<String> tagOverrides(String watchUuid, String attribute) {
    Map<String, List<String>> forWatch = tagOverrides.get(watchUuid);
    if (forWatch == null || !forWatch.containsKey(attribute)) {
      return new ArrayList<>();
    }
    return forWatch.get(attribute);
  }

  @Override
  public Map<String, Object> application() {
    return applicationSettings();
  }

  @Override
  public String lastRawContentChecksum(String watchUuid) {
    return rawChecksums.get(watchUuid);
  }

  @Override
  public void updateLastRawContentChecksum(String watchUuid, String checksum) {
    rawChecksums.put(watchUuid, checksum);
  }

  @Override
  public String snapshot(String watchUuid, long timestamp) {
    Map<Long, String> forWatch = snapshots.get(watchUuid);
    return forWatch == null ? null : forWatch.get(timestamp);
  }

  @Override
  public String lastFetchedTextBeforeFilters(String watchUuid) {
    return textBeforeFilters.get(watchUuid);
  }

  @Override
  public void saveLastFetchedTextBeforeFilters(String watchUuid, String text) {
    textBeforeFilters.put(watchUuid, text);
  }

  @Override
  public boolean conditionsAllow(Watch watch, String text) {
    return io.akka.changedetection.conditions.RuleSet.evaluate(watch, text);
  }

  @Override
  public String pdfToHtml(byte[] rawContent) {
    throw new UnsupportedOperationException("no document converter in the probe");
  }
}
