package io.akka.changedetection.processors;

import java.util.LinkedHashMap;
import java.util.Map;

/** What one check decided, and what it wants written down. */
public record CheckOutcome(boolean changed, Map<String, Object> updates, String contents) {

  public static CheckOutcome of(boolean changed, Map<String, Object> updates, String contents) {
    return new CheckOutcome(changed, updates == null ? new LinkedHashMap<>() : updates, contents);
  }
}
