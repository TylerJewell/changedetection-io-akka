package io.akka.changedetection;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.changedetection.api.WatchEndpoint;
import io.akka.changedetection.domain.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reachable surface, over HTTP. Question-log row 24 records the same thing done by hand
 * against the built service; this keeps it from rotting.
 */
class WatchEndpointIntegrationTest extends TestKitSupport {

  private static final String UNREACHABLE = "http://127.0.0.1:1/never-fetched";

  private void create(String id, WatchEndpoint.WatchRequest request) {
    var response =
        httpClient.POST("/watches/" + id).withRequestBody(request).invoke();
    assertThat(response.httpResponse().status()).isEqualTo(StatusCodes.CREATED);
  }

  private WatchEndpoint.CheckResponse submit(String id, String body) {
    return httpClient
        .POST("/watches/" + id + "/submit")
        .withRequestBody(new WatchEndpoint.BodyRequest(body, false))
        .responseBodyAs(WatchEndpoint.CheckResponse.class)
        .invoke()
        .body();
  }

  @Test
  void theFalsePositiveRuleIsReachableOverHttp() {
    String id = "endpoint-ignore";
    // The interval is a day and the URL is unreachable, so the watch's own clock cannot fire a
    // fetch during the test; every verdict below comes from a body offered by hand.
    create(
        id,
        new WatchEndpoint.WatchRequest(
            UNREACHABLE, 86_400, null, List.of("Last updated"), null, null, null, null, null));

    var first = submit(id, "Price: 10\nLast updated: 1");
    assertThat(first.verdict()).isEqualTo(Verdict.CHANGED);
    assertThat(first.worthReporting()).isFalse();

    assertThat(submit(id, "Price: 10\nLast updated: 2").verdict()).isEqualTo(Verdict.UNCHANGED);

    var real = submit(id, "Price: 11\nLast updated: 3");
    assertThat(real.verdict()).isEqualTo(Verdict.CHANGED);
    assertThat(real.worthReporting()).isTrue();
  }

  @Test
  void statusReportsWhatTheLastCheckConcluded() {
    String id = "endpoint-status";
    create(
        id,
        new WatchEndpoint.WatchRequest(
            UNREACHABLE, 86_400, null, null, null, null, null, null, null));
    submit(id, "A");
    submit(id, "A");

    var status =
        httpClient
            .GET("/watches/" + id)
            .responseBodyAs(WatchEndpoint.StatusResponse.class)
            .invoke()
            .body();
    assertThat(status.lastVerdict()).isEqualTo(Verdict.UNCHANGED_RAW_IDENTICAL);
    assertThat(status.snapshotsKept()).isEqualTo(1);
    assertThat(status.config().url()).isEqualTo(UNREACHABLE);
  }

  @Test
  void theDifferenceBetweenTheLastTwoSnapshotsIsReadable() {
    String id = "endpoint-diff";
    create(
        id,
        new WatchEndpoint.WatchRequest(
            UNREACHABLE, 86_400, null, null, null, null, null, null, null));
    submit(id, "A\nB\nC");
    submit(id, "A\nX\nC");

    var diff = httpClient
            .GET("/watches/" + id + "/diff")
            .parseResponseBody(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            .invoke()
            .body();
    assertThat(diff.lines().toList()).containsExactly("B", "X");
  }

  @Test
  void aTriggerBlocksUntilItAppears() {
    String id = "endpoint-trigger";
    create(
        id,
        new WatchEndpoint.WatchRequest(
            UNREACHABLE, 86_400, null, null, List.of("In stock"), null, null, null, null));
    assertThat(submit(id, "Out of stock").verdict()).isEqualTo(Verdict.BLOCKED_NO_TRIGGER);
    assertThat(submit(id, "In stock").verdict()).isEqualTo(Verdict.CHANGED);
  }
}
