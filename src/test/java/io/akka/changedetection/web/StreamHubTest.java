package io.akka.changedetection.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What a reader of the stream is given, and what happens when it stops reading.
 *
 * <p>The second half is the one worth checking. A reader that goes away leaves a subscriber
 * behind with no request to notice it by, so a hub that only ever adds is a hub that grows for
 * as long as the service runs.
 */
class StreamHubTest {

  private static StreamHub.Event await(CompletionStage<StreamHub.Event> pending) throws Exception {
    return pending.toCompletableFuture().get(2, TimeUnit.SECONDS);
  }

  @Test
  void aReaderIsGivenWhatIsPublishedAfterItSubscribed() throws Exception {
    StreamHub.Subscriber reader = StreamHub.subscribe();
    try {
      CompletionStage<StreamHub.Event> pending = reader.next();
      StreamHub.publish("queue_size", Map.of("q_length", 3));
      StreamHub.Event event = await(pending);
      assertEquals("queue_size", event.name());
      assertTrue(event.payload().toString().contains("\"q_length\":3"));
    } finally {
      StreamHub.unsubscribe(reader);
    }
  }

  @Test
  void somethingPublishedBeforeTheReaderAsksIsHeldRatherThanLost() throws Exception {
    StreamHub.Subscriber reader = StreamHub.subscribe();
    try {
      StreamHub.publish("watch_update", Map.of("watch", Map.of("uuid", "abc")));
      StreamHub.Event event = await(reader.next());
      assertEquals("watch_update", event.name());
      assertTrue(event.payload().toString().contains("abc"));
    } finally {
      StreamHub.unsubscribe(reader);
    }
  }

  @Test
  void everyEventCarriesTheMomentItHappened() throws Exception {
    StreamHub.Subscriber reader = StreamHub.subscribe();
    try {
      StreamHub.publish("toast", Map.of("message", "hello"));
      StreamHub.Event event = await(reader.next());
      assertTrue(event.payload().toString().contains("event_timestamp"), event.payload().toString());
    } finally {
      StreamHub.unsubscribe(reader);
    }
  }

  @Test
  void aReaderThatUnsubscribesIsGivenNothingFurther() throws Exception {
    StreamHub.Subscriber reader = StreamHub.subscribe();
    int before = StreamHub.subscriberCount();
    StreamHub.unsubscribe(reader);
    assertEquals(before - 1, StreamHub.subscriberCount());

    StreamHub.publish("queue_size", Map.of("q_length", 9));
    assertNull(await(reader.next()), "a closed reader is told the stream is over");
  }

  @Test
  void aReaderThatStopsReadingIsDroppedRatherThanHeldForever() throws Exception {
    StreamHub.Subscriber reader = StreamHub.subscribe();
    int before = StreamHub.subscriberCount();
    // Never asks for anything, so the backlog fills.
    for (int index = 0; index < 400; index++) {
      StreamHub.publish("queue_size", Map.of("q_length", index));
    }
    assertTrue(
        StreamHub.subscriberCount() < before,
        "the reader that never read was given up on");
    assertNull(await(reader.next()));
  }

  @Test
  void severalReadersAreEachGivenTheSameThing() throws Exception {
    StreamHub.Subscriber first = StreamHub.subscribe();
    StreamHub.Subscriber second = StreamHub.subscribe();
    try {
      StreamHub.publish("general_stats_update", Map.of("watch_count", 7));
      StreamHub.Event toFirst = await(first.next());
      StreamHub.Event toSecond = await(second.next());
      assertNotNull(toFirst);
      assertEquals(toFirst.name(), toSecond.name());
      assertEquals(toFirst.payload(), toSecond.payload());
    } finally {
      StreamHub.unsubscribe(first);
      StreamHub.unsubscribe(second);
    }
  }

  @Test
  void aPayloadThatIsNotASetOfNamedValuesStillArrives() throws Exception {
    StreamHub.Subscriber reader = StreamHub.subscribe();
    try {
      StreamHub.publish("toast", "just a sentence");
      StreamHub.Event event = await(reader.next());
      assertTrue(event.payload().toString().contains("just a sentence"), event.payload().toString());
    } finally {
      StreamHub.unsubscribe(reader);
    }
  }
}
