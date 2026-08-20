package io.akka.changedetection.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.http.HttpResponses;
import io.akka.changedetection.application.WatchEntity;
import io.akka.changedetection.application.WatchWorkflow;
import io.akka.changedetection.domain.ContentType;
import io.akka.changedetection.domain.Interval;
import io.akka.changedetection.domain.Verdict;
import io.akka.changedetection.domain.WatchConfig;
import java.time.Instant;
import java.util.List;

/**
 * The surface: register a watch, let it run on its own clock, offer a body for judgement by hand,
 * and read back what the watch concluded and what changed.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/watches")
public class WatchEndpoint {

  private final ComponentClient client;

  public WatchEndpoint(ComponentClient client) {
    this.client = client;
  }

  /** Everything an operator sets, with the rule lists optional. */
  public record WatchRequest(
      String url,
      Integer intervalSeconds,
      Boolean paused,
      List<String> ignoreText,
      List<String> triggerText,
      List<String> forbiddenText,
      Boolean checkUniqueLines,
      Boolean stripIgnoredLines,
      Boolean ignoreWhitespace) {}

  public record BodyRequest(String body, Boolean html) {}

  public record CheckResponse(Verdict verdict, boolean worthReporting, String snapshot) {}

  public record StatusResponse(
      WatchConfig config, Verdict lastVerdict, int snapshotsKept, long lastCheckedEpochSeconds) {}


  @Post("/{id}")
  public HttpResponse create(String id, WatchRequest request) {
    client.forEventSourcedEntity(id).method(WatchEntity::configure).invoke(toConfig(request));
    client.forWorkflow(id).method(WatchWorkflow::start).invoke(id);
    return HttpResponses.created(id, "/watches/" + id);
  }

  @Put("/{id}")
  public HttpResponse update(String id, WatchRequest request) {
    client.forEventSourcedEntity(id).method(WatchEntity::configure).invoke(toConfig(request));
    // A new interval only takes effect once the timer carrying the old one is replaced.
    client.forWorkflow(id).method(WatchWorkflow::reschedule).invoke();
    return HttpResponses.ok(id);
  }

  /** Offer a body directly, bypassing the fetch. The rules that judge it are the same ones. */
  @Post("/{id}/submit")
  public CheckResponse submit(String id, BodyRequest request) {
    var result =
        client
            .forEventSourcedEntity(id)
            .method(WatchEntity::submit)
            .invoke(
                new WatchEntity.Submission(
                    request.body(),
                    Boolean.TRUE.equals(request.html()) ? ContentType.HTML : ContentType.PLAIN,
                    Instant.now().getEpochSecond()));
    return new CheckResponse(result.verdict(), result.worthReporting(), result.snapshot());
  }

  @Get("/{id}")
  public StatusResponse status(String id) {
    var state = client.forEventSourcedEntity(id).method(WatchEntity::status).invoke();
    return new StatusResponse(
        state.config(),
        state.lastVerdict(),
        state.history().size(),
        state.lastCheckedEpochSeconds());
  }

  @Get("/{id}/diff")
  public String diff(String id) {
    return client.forEventSourcedEntity(id).method(WatchEntity::latestDiff).invoke();
  }

  private static WatchConfig toConfig(WatchRequest request) {
    var config = WatchConfig.of(request.url());
    if (request.intervalSeconds() != null) {
      config = config.withInterval(Interval.ofSeconds(request.intervalSeconds()));
    }
    if (request.paused() != null) {
      config = config.withPaused(request.paused());
    }
    if (request.ignoreText() != null) {
      config = config.withIgnoreText(request.ignoreText());
    }
    if (request.triggerText() != null) {
      config = config.withTriggerText(request.triggerText());
    }
    if (request.forbiddenText() != null) {
      config = config.withForbiddenText(request.forbiddenText());
    }
    if (request.checkUniqueLines() != null) {
      config = config.withCheckUniqueLines(request.checkUniqueLines());
    }
    if (request.stripIgnoredLines() != null) {
      config = config.withStripIgnoredLines(request.stripIgnoredLines());
    }
    if (request.ignoreWhitespace() != null) {
      config = config.withIgnoreWhitespace(request.ignoreWhitespace());
    }
    return config;
  }
}
