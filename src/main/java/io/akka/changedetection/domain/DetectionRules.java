package io.akka.changedetection.domain;

import java.util.Optional;
import java.util.Set;

/**
 * The decision procedure: given what an operator asked for, what the watch already knows, and
 * what came back, decide whether this counts as a change. SPEC-001 §3 R8, R10-R18.
 *
 * <p>The order of the stages is the rule, not an implementation detail. Trigger text is judged
 * after ignored lines are gone and forbidden text before, so an ignored line can silence a
 * trigger but cannot stop blocking; a blocked verdict records nothing, so returning to an earlier
 * body after a block is not a change.
 */
public final class DetectionRules {

  private DetectionRules() {}

  public static Outcome decide(
      WatchConfig config, DetectionState state, String rawBody, ContentType contentType) {

    String rawChecksum = TextPreparation.md5(rawBody);
    String fingerprint = config.ruleFingerprint();

    // R8 — an identical body under an identical rule set cannot produce a different answer, so
    // no rule is consulted. The rule set is part of the test: without it, editing a rule and
    // re-fetching the same page would keep answering with the old rule's verdict.
    if (state.lastRawChecksum().map(rawChecksum::equals).orElse(false)
        && state.lastRuleFingerprint().map(fingerprint::equals).orElse(false)) {
      return blocked(Verdict.UNCHANGED_RAW_IDENTICAL, "", rawChecksum, fingerprint);
    }

    String text = TextPreparation.toText(rawBody, contentType);
    String withoutIgnored = TextPreparation.stripIgnored(text, config.ignoreText());

    // R11 — the stored snapshot keeps ignored lines unless the operator asked otherwise.
    String snapshot = config.stripIgnoredLines() ? withoutIgnored : text;

    // R12 — judged after ignore-removal, so an ignored line cannot fire a trigger.
    if (!config.triggerText().isEmpty()
        && !TextPreparation.containsAny(withoutIgnored, config.triggerText())) {
      return blocked(Verdict.BLOCKED_NO_TRIGGER, snapshot, rawChecksum, fingerprint);
    }

    // R13 — judged on the snapshot, which still carries ignored lines unless stripped, so an
    // ignored line can still block.
    if (!config.forbiddenText().isEmpty()
        && TextPreparation.containsAny(snapshot, config.forbiddenText())) {
      return blocked(Verdict.BLOCKED_FORBIDDEN, snapshot, rawChecksum, fingerprint);
    }

    String checksum = TextPreparation.checksum(withoutIgnored, config.ignoreWhitespace());
    boolean changed = !state.previousChecksum().map(checksum::equals).orElse(false);

    // R15 — the checksum is recorded whether or not it changed. Only a block skips this.
    if (!changed) {
      return new Outcome(
          Verdict.UNCHANGED, snapshot, rawChecksum, fingerprint, Optional.of(checksum), Set.of());
    }

    // R18 — a change every one of whose lines has been recorded before is not reported. The
    // checksum still moves, so the same body arriving twice more does not re-ask the question.
    Set<String> unseen = state.unseenLinesOf(snapshot);
    if (config.checkUniqueLines() && unseen.isEmpty()) {
      return new Outcome(
          Verdict.UNCHANGED_NO_UNIQUE_LINES,
          snapshot,
          rawChecksum,
          fingerprint,
          Optional.of(checksum),
          Set.of());
    }

    return new Outcome(
        Verdict.CHANGED, snapshot, rawChecksum, fingerprint, Optional.of(checksum), unseen);
  }

  private static Outcome blocked(
      Verdict verdict, String snapshot, String rawChecksum, String fingerprint) {
    return new Outcome(verdict, snapshot, rawChecksum, fingerprint, Optional.empty(), Set.of());
  }
}
