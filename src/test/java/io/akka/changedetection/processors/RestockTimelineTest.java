package io.akka.changedetection.processors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A price history reads back the way the original reads it. */
class RestockTimelineTest {

  private static List<RestockTimeline.Point> pointsFor(double... prices) {
    List<RestockTimeline.Point> series = new ArrayList<>();
    long at = 1000;
    for (double price : prices) {
      series.add(new RestockTimeline.Point(at++, price, true));
    }
    return series;
  }

  @Test
  void aStoredCheckIsReadBack() {
    RestockTimeline.Point point = RestockTimeline.parse(42, "In Stock: True - Price: 12.34");
    assertEquals(42, point.timestamp());
    assertEquals(12.34, point.price());
    assertEquals(Boolean.TRUE, point.inStock());
  }

  @Test
  void aCheckThatSawNeitherReadsAsNeither() {
    RestockTimeline.Point point = RestockTimeline.parse(42, "nothing useful here");
    assertNull(point.price());
    assertNull(point.inStock());
  }

  @Test
  void theQuartilesMatchTheOriginals() {
    assertEquals(1.25, RestockTimeline.quantile(List.of(1.0, 2.0), 0.25), 1e-9);
    assertEquals(1.5, RestockTimeline.quantile(List.of(1.0, 2.0), 0.50), 1e-9);
    assertEquals(1.75, RestockTimeline.quantile(List.of(1.0, 2.0), 0.75), 1e-9);
    assertEquals(1.75, RestockTimeline.quantile(List.of(1.0, 2.0, 3.0, 4.0), 0.25), 1e-9);
    assertEquals(3.25, RestockTimeline.quantile(List.of(1.0, 2.0, 3.0, 4.0), 0.75), 1e-9);
    assertEquals(2.0, RestockTimeline.quantile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.25), 1e-9);
  }

  @Test
  void aPriceAtTheBottomOfItsRangeReadsAsLow() {
    Map<String, Object> summary = RestockTimeline.priceSummary(pointsFor(10.0, 9.0, 8.0, 5.0));
    assertEquals("low", summary.get("status"));
    assertEquals(Boolean.TRUE, summary.get("all_time_low"));
    assertEquals(5.0, summary.get("current"));
    // Three of the four checks saw a higher price than this one.
    assertEquals(75L, summary.get("cheaper_than_pct"));
    assertEquals(0L, summary.get("pricier_than_pct"));
  }

  @Test
  void aPriceAtTheTopOfItsRangeReadsAsHigh() {
    Map<String, Object> summary = RestockTimeline.priceSummary(pointsFor(5.0, 8.0, 9.0, 10.0));
    assertEquals("high", summary.get("status"));
    assertEquals(Boolean.FALSE, summary.get("all_time_low"));
    assertEquals(0L, summary.get("cheaper_than_pct"));
    assertEquals(75L, summary.get("pricier_than_pct"));
  }

  @Test
  void aHistoryWithNoPricesHasNoSummary() {
    List<RestockTimeline.Point> series = new ArrayList<>();
    series.add(new RestockTimeline.Point(1, null, false));
    assertNull(RestockTimeline.priceSummary(series));
  }

  @Test
  void aMiddlingPriceReadsAsTypical() {
    Map<String, Object> summary = RestockTimeline.priceSummary(pointsFor(1.0, 4.0, 2.5));
    assertEquals("typical", summary.get("status"));
    assertTrue(((java.lang.Number) summary.get("median")).doubleValue() > 0);
  }
}
