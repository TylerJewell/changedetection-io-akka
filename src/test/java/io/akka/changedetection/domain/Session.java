package io.akka.changedetection.domain;

/**
 * A run of consecutive checks against one configuration, carrying the state forward the way the
 * entity does. Consecutive calls model consecutive scheduled checks, which is what most of the
 * rules in SPEC-001 §3 are about — no single check can show them.
 */
final class Session {

  private final WatchConfig config;
  private DetectionState state = DetectionState.empty();

  Session(WatchConfig config) {
    this.config = config;
  }

  Outcome check(String body) {
    var outcome = DetectionRules.decide(config, state, body, ContentType.PLAIN);
    state = outcome.applyTo(state);
    return outcome;
  }

  Verdict verdictOf(String body) {
    return check(body).verdict();
  }

  DetectionState state() {
    return state;
  }
}
