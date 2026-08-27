package io.akka.changedetection.processors;

import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.text.PythonText;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The decision for a watch that is following a product rather than a page.
 *
 * <p>What it compares is not the page: it is two facts read off the page, whether the thing can
 * be bought and what it costs. So a page that redraws itself constantly is quiet here while a
 * page whose price moved by a penny is not -- which is the whole reason the mode exists.
 *
 * <p>Three settings narrow it further, and they compose in an order that matters. A price
 * inside the operator's acceptable band cancels the change outright; a price outside it is
 * then subject to a minimum percentage move; and a stock change is decided separately from
 * either.
 */
public final class RestockProcessor {

  /** What this processor needs beyond the watch itself. */
  public interface Environment {
    String lastRawContentChecksum(String watchUuid);

    void updateLastRawContentChecksum(String watchUuid, String checksum);

    Map<String, Object> application();

    /** The processor's own settings for this watch, kept apart from the watch's own fields. */
    Map<String, Object> processorConfig(String watchUuid, String name);

    /** A tag's settings for this processor, where a tag is set to override the watch. */
    Map<String, Object> tagRestockOverride(Watch watch);

    /** A stored version, which the picture comparison reads to get the previous picture. */
    String snapshotFor(String watchUuid, long timestamp);

    /**
     * The last resort for a page that publishes no usable structured data.
     *
     * @return the price and availability read from the page, or null when none was worked out
     */
    Map<String, Object> priceAndStockFallback(Watch watch, String htmlContent);
  }

  private static long asLong(Object value) {
    return value instanceof Number number ? number.longValue() : 0;
  }

  private final Environment environment;

  public RestockProcessor(Environment environment) {
    this.environment = environment;
  }

  public CheckOutcome run(Watch watch, Fetched fetched) {
    return run(watch, fetched, false);
  }

  public CheckOutcome run(Watch watch, Fetched fetched, boolean forceReprocess) {
    String currentRawChecksum = PythonText.md5Hex(fetched.rawContent);
    String lastRawChecksum = environment.lastRawContentChecksum(watch.uuid());
    boolean rawChanged =
        lastRawChecksum == null || !lastRawChecksum.equals(currentRawChecksum);

    if (!forceReprocess && !watch.wasEdited() && lastRawChecksum != null && !rawChanged) {
      throw new ProcessorExceptions.ChecksumWasTheSame();
    }

    Map<String, Object> updates = new LinkedHashMap<>();
    updates.put("last_notification_error", false);
    updates.put("last_error", false);
    String contentType = fetched.header("Content-Type");
    updates.put("content-type", contentType == null ? "" : contentType);
    updates.put("last_check_status", fetched.statusCode);
    environment.updateLastRawContentChecksum(watch.uuid(), currentRawChecksum);

    Map<String, Object> settings = environment.tagRestockOverride(watch);
    if (settings == null) {
      Map<String, Object> own = environment.processorConfig(watch.uuid(), "restock_diff.json");
      Object nested = own.get("restock_diff");
      if (nested instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        settings = typed;
      } else {
        settings = new LinkedHashMap<>();
        settings.put("follow_price_changes", true);
        settings.put("in_stock_processing", "in_stock_only");
      }
    }
    Fields config = new Fields(settings);

    Restock found = new Restock();
    boolean multiplePrices = false;
    try {
      found = ItemPropExtractor.extract(fetched.content);
    } catch (ItemPropExtractor.MoreThanOnePriceFound e) {
      multiplePrices = true;
    }

    // The shop's own structured data is preferred; the model is asked only for what it did
    // not carry, and only when the operator left the fallback switched on.
    if (found.price == null || found.availability == null) {
      Map<String, Object> better =
          environment.priceAndStockFallback(watch, fetched.content);
      if (better != null) {
        long tokens = asLong(better.get("_tokens"));
        if (tokens > 0) {
          watch.updateSystem(
              Map.of(
                  "llm_last_tokens_used", tokens,
                  "llm_tokens_used_cumulative",
                      asLong(watch.fields().get("llm_tokens_used_cumulative")) + tokens));
        }
        Object price = better.get("price");
        Object availability = better.get("availability");
        if (price != null || availability != null) {
          Restock replacement = new Restock();
          replacement.price = price instanceof Number number ? number.doubleValue() : null;
          Object currency = better.get("currency");
          replacement.currency = currency == null ? null : String.valueOf(currency);
          replacement.availability = availability == null ? null : String.valueOf(availability);
          found = replacement;
          multiplePrices = false;
        }
      }
    }

    if (multiplePrices && found.price == null) {
      throw new ProcessorExceptions.ProcessorException(
          "Cannot run, more than one price detected, this plugin is only for product pages "
              + "with ONE product, try the content-change detection mode.",
          fetched.statusCode);
    }

    Restock current = new Restock();
    if (found.price != null || found.availability != null) {
      current.price = found.price;
      current.currency = found.currency;
      current.availability = found.availability;
      if (found.availability != null) {
        current.inStock = Restock.meansAvailable(found.availability);
      }
    }

    Restock previous = new Restock(watch.fields().map("restock"));

    // The reference price moves only when the price moves, so a watch checked hourly against a
    // price that has not changed for a week still knows what it changed from when it does.
    if (current.price != null && !current.price.equals(previous.price)) {
      current.lastPrice = previous.price;
    } else {
      current.lastPrice = previous.lastPrice;
    }

    if (fetched.instockData == null && found.availability == null && found.price == null) {
      throw new ProcessorExceptions.ProcessorException(
          "Unable to extract restock data for this page unfortunately. (Got code "
              + fetched.statusCode
              + " from server), no embedded stock information was found and nothing interesting "
              + "in the text, try using this watch with Chrome.",
          fetched.statusCode);
    }

    if (fetched.instockData != null && found.availability == null) {
      current.inStock = "Possibly in stock".equals(fetched.instockData);
    }

    // Where the page's own statement and what is written on it disagree, what is written wins:
    // a page that says available in its metadata and "sold out" in its text is sold out.
    if (fetched.instockData != null && !"Possibly in stock".equals(fetched.instockData)) {
      if (Boolean.TRUE.equals(current.inStock)) {
        current.inStock = false;
      }
    }

    String priceText = current.price == null ? "" : formatPrice(current.price);
    String snapshot = "In Stock: " + current.inStock + " - Price: " + priceText;
    String fetchedMd5 = PythonText.md5Hex(snapshot);

    boolean changed = false;
    String inStockProcessing = config.string("in_stock_processing", "in_stock_only");

    boolean hadPrevious = watch.fields().has("restock") && !watch.fields().map("restock").isEmpty();
    if (hadPrevious && !java.util.Objects.equals(previous.inStock, current.inStock)) {
      if ("in_stock_only".equals(inStockProcessing) && Boolean.TRUE.equals(current.inStock)) {
        changed = true;
      }
      if ("all_changes".equals(inStockProcessing)) {
        changed = true;
      }
    }

    if (config.bool("follow_price_changes", true) && hadPrevious && current.price != null) {
      Double previousPrice = previous.price;
      if (previousPrice != null && !previousPrice.equals(current.price)) {
        changed = true;
      }

      Double minimum = config.number("price_change_min");
      Double maximum = config.number("price_change_max");
      if (minimum != null || maximum != null) {
        boolean inside =
            (minimum == null || minimum <= current.price)
                && (maximum == null || current.price <= maximum);
        if (inside) {
          changed = false;
        }
      }

      Double threshold = config.number("price_change_threshold_percent");
      if (previousPrice != null && previousPrice != 0 && changed && threshold != null) {
        double move = Math.abs((current.price - previousPrice) / previousPrice * 100.0);
        if (move != 0 && move <= threshold) {
          changed = false;
        }
      }
    }

    updates.put("restock", current.asMap());
    updates.put("previous_md5", fetchedMd5);

    return CheckOutcome.of(changed, updates, snapshot.strip());
  }

  /** A price written the way the stored version writes it, which the comparison then reads. */
  static String formatPrice(double price) {
    if (price == Math.rint(price) && Math.abs(price) < 1e15) {
      return String.valueOf((long) price);
    }
    return io.akka.changedetection.text.PythonJson.floatRepr(price);
  }

  /** The price a stored version recorded, read back out of it. */
  public static Double priceFromSnapshot(String snapshot) {
    if (snapshot == null) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("Price:\\s*(\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(snapshot);
    if (!matcher.find()) {
      return null;
    }
    try {
      return Double.valueOf(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
