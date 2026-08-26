package io.akka.changedetection.probe;

import com.fasterxml.jackson.databind.JsonNode;

/** The last of the probe surface; new questions land here. */
final class ProbeOpsPart4 {

  private ProbeOpsPart4() {}

  static JsonNode dispatch(String op, JsonNode args) {
    throw new IllegalArgumentException("unknown probe op: " + op);
  }
}
