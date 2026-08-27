package io.akka.changedetection.processors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A price watch's whole history, read back out of its stored versions.
 *
 * <p>A price watch stores each check as one short line rather than as a page, so the history is
 * read back by parsing those lines. That is also why its history page shows a graph rather than
 * a difference: comparing two of these lines as text would say "the number changed" and nothing
 * more.
 */
public final class RestockTimeline {

  private static final Pattern PRICE =
      Pattern.compile("Price:\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE);

  private static final Pattern IN_STOCK =
      Pattern.compile("In Stock:\\s*(True|False)", Pattern.CASE_INSENSITIVE);

  private RestockTimeline() {}

  /** One check: when it ran, what the price was, and whether the item could be bought. */
  public record Point(long timestamp, Double price, Boolean inStock) {
    public Map<String, Object> asMap() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("timestamp", timestamp);
      out.put("price", price);
      out.put("in_stock", inStock);
      return out;
    }
  }

  public static Point parse(long timestamp, String snapshot) {
    Double price = null;
    Boolean inStock = null;
    if (snapshot != null) {
      Matcher priceMatch = PRICE.matcher(snapshot);
      if (priceMatch.find()) {
        try {
          price = Double.parseDouble(priceMatch.group(1));
        } catch (NumberFormatException e) {
          price = null;
        }
      }
      Matcher stockMatch = IN_STOCK.matcher(snapshot);
      if (stockMatch.find()) {
        inStock = stockMatch.group(1).equalsIgnoreCase("true");
      }
    }
    return new Point(timestamp, price, inStock);
  }

  /**
   * Where the current price sits against everything this watch has seen.
   *
   * <p>Two shares are reported rather than one, because "cheaper than 80% of the time" and
   * "dearer than 80% of the time" are the two things a person actually wants to know, and one
   * number would leave the reader to work out which they were looking at.
   *
   * @return null when no check ever saw a price
   */
  public static Map<String, Object> priceSummary(List<Point> series) {
    List<Double> prices = new ArrayList<>();
    for (Point point : series) {
      if (point.price() != null) {
        prices.add(point.price());
      }
    }
    if (prices.isEmpty()) {
      return null;
    }
    int count = prices.size();
    double current = prices.get(count - 1);
    List<Double> ordered = new ArrayList<>(prices);
    Collections.sort(ordered);
    double lowest = ordered.get(0);
    double highest = ordered.get(count - 1);
    double total = 0;
    for (double price : prices) {
      total += price;
    }
    double average = total / count;

    double median;
    double lowerQuartile;
    double upperQuartile;
    if (count >= 2) {
      lowerQuartile = quantile(ordered, 0.25);
      median = quantile(ordered, 0.50);
      upperQuartile = quantile(ordered, 0.75);
    } else {
      median = current;
      lowerQuartile = current;
      upperQuartile = current;
    }

    int dearerCount = 0;
    int cheaperCount = 0;
    for (double price : prices) {
      if (price > current) {
        dearerCount++;
      }
      if (price < current) {
        cheaperCount++;
      }
    }

    String status;
    if (current <= lowerQuartile) {
      status = "low";
    } else if (current >= upperQuartile) {
      status = "high";
    } else {
      status = "typical";
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("count", count);
    summary.put("min", round(lowest));
    summary.put("max", round(highest));
    summary.put("avg", round(average));
    summary.put("median", round(median));
    summary.put("p25", round(lowerQuartile));
    summary.put("p75", round(upperQuartile));
    summary.put("current", round(current));
    summary.put("cheaper_than_pct", Math.round(100.0 * dearerCount / count));
    summary.put("pricier_than_pct", Math.round(100.0 * cheaperCount / count));
    summary.put("status", status);
    summary.put("all_time_low", current <= lowest);
    return summary;
  }

  /**
   * A quantile the way the language the original is written in computes one.
   *
   * <p>The inclusive method: the points are placed at the ends of the range rather than inside
   * it, so with two prices the lower quartile is a quarter of the way between them and not the
   * lower of the two.
   */
  static double quantile(List<Double> ordered, double fraction) {
    int count = ordered.size();
    if (count == 1) {
      return ordered.get(0);
    }
    double position = fraction * (count - 1);
    int lower = (int) Math.floor(position);
    int upper = (int) Math.ceil(position);
    if (lower == upper) {
      return ordered.get(lower);
    }
    double weight = position - lower;
    return ordered.get(lower) * (1 - weight) + ordered.get(upper) * weight;
  }

  static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }
}
