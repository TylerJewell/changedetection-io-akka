package io.akka.changedetection.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.changedetection.domain.ContentType;
import io.akka.changedetection.domain.DetectionRules;
import io.akka.changedetection.domain.Diff;
import io.akka.changedetection.domain.Verdict;
import io.akka.changedetection.domain.WatchConfig;
import io.akka.changedetection.domain.WatchEvent;
import io.akka.changedetection.domain.WatchState;
import java.util.EnumSet;

/**
 * One watched page: what an operator asked for, what has been seen, and what the last check
 * concluded. SPEC-001 §3 R15, R16, R18, R19.
 *
 * <p>The entity holds the decision, not the fetching — a body arrives here already fetched, so
 * the rules can be exercised without a network and the same rules decide either way.
 */
@Component(id = "watch")
public class WatchEntity extends EventSourcedEntity<WatchState, WatchEvent> {

  /**
   * Longest body this entity will judge. Bodies are held in state and in the journal, both of
   * which the runtime replicates whole, so the limit belongs here rather than only at the
   * fetcher — a body can also arrive through the endpoint.
   */
  public static final int MAX_BODY_CHARS = 512_000;

  /** A fetched body offered for judgement. */
  public record Submission(String body, ContentType contentType, long atEpochSeconds) {}

  /**
   * What the check concluded.
   *
   * @param worthReporting whether this is worth telling someone about. A first-ever change is a change
   *     and is not worth a notification: there is nothing to compare it against (R19).
   */
  public record CheckResult(Verdict verdict, boolean worthReporting, String snapshot) {}

  @Override
  public WatchState emptyState() {
    return WatchState.empty();
  }

  public Effect<Done> configure(WatchConfig config) {
    return effects().persist(new WatchEvent.Configured(config)).thenReply(s -> Done.getInstance());
  }

  public Effect<CheckResult> submit(Submission submission) {
    var state = currentState();
    if (!state.isConfigured()) {
      return effects().error("watch has not been configured");
    }
    if (submission.body().length() > MAX_BODY_CHARS) {
      return effects().error("body exceeds " + MAX_BODY_CHARS + " characters");
    }
    boolean hadHistory = !state.history().isEmpty();
    var outcome =
        DetectionRules.decide(
            state.config(), state.detection(), submission.body(), submission.contentType());
    return effects()
        .persist(
            new WatchEvent.Checked(
                outcome.verdict(),
                // Only a change adds to the history, so only a change needs its text kept.
                outcome.verdict().isChange() ? outcome.snapshot() : "",
                outcome.rawChecksum(),
                outcome.ruleFingerprint(),
                outcome.recordedChecksum(),
                outcome.newlySeenLines(),
                submission.atEpochSeconds()))
        .thenReply(
            s ->
                new CheckResult(
                    outcome.verdict(),
                    outcome.verdict().isChange() && hadHistory,
                    outcome.snapshot()));
  }

  public ReadOnlyEffect<WatchState> status() {
    return effects().reply(currentState());
  }

  /** The difference between the two most recent recorded snapshots. SPEC-001 §3 R20. */
  public ReadOnlyEffect<String> latestDiff() {
    var history = currentState().history();
    if (history.size() < 2) {
      return effects().reply("");
    }
    return effects()
        .reply(
            Diff.render(
                history.get(history.size() - 2),
                history.get(history.size() - 1),
                EnumSet.allOf(Diff.Kind.class)));
  }

  @Override
  public WatchState applyEvent(WatchEvent event) {
    return switch (event) {
      case WatchEvent.Configured e -> currentState().configured(e.config());
      case WatchEvent.Checked e ->
          currentState()
              .checked(
                  e.verdict(),
                  currentState()
                      .detection()
                      .applying(
                          e.recordedChecksum(),
                          e.rawChecksum(),
                          e.ruleFingerprint(),
                          e.newlySeenLines()),
                  e.snapshot(),
                  e.atEpochSeconds());
    };
  }
}
