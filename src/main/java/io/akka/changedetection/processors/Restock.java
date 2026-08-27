package io.akka.changedetection.processors;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a product page said about stock and price.
 *
 * <p>A price arrives as whatever the page wrote, so reading it is the whole problem: "1,400.00"
 * and "1.400,00" are the same amount written under two conventions, and reading one as the
 * other is a hundred-fold error in a watch whose purpose is to notice a price move.
 */
public final class Restock {

  private static final Pattern NON_NUMERIC = Pattern.compile("[^\\d.-]");

  public Boolean inStock;
  public Double price;
  public String currency;
  public Double lastPrice;
  public String availability;

  public Restock() {}

  public Restock(Map<String, Object> values) {
    if (values == null) {
      return;
    }
    Object stock = values.get("in_stock");
    if (stock != null) {
      inStock = stock instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(stock));
    }
    price = parseValue(values.get("price"));
    lastPrice = parseValue(values.get("last_price"));
    Object currencyValue = values.get("currency");
    currency = currencyValue == null ? null : String.valueOf(currencyValue);
    Object availabilityValue = values.get("availability");
    availability = availabilityValue == null ? null : String.valueOf(availabilityValue);
  }

  private static Double parseValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    return parseCurrency(String.valueOf(value));
  }

  /**
   * A written amount read as a number.
   *
   * <p>Where both separators appear, the last one is the decimal point -- that is what
   * distinguishes the two conventions. Where only one appears, it is treated as the decimal
   * point, which is why a thousands separator on its own reads low; the original does the same
   * and a page that writes one is rare next to a page that writes a decimal comma.
   */
  public static Double parseCurrency(String rawValue) {
    if (rawValue == null) {
      return null;
    }
    String standardised = rawValue;
    if (standardised.indexOf(',') >= 0 && standardised.indexOf('.') >= 0) {
      if (standardised.lastIndexOf('.') > standardised.lastIndexOf(',')) {
        standardised = standardised.replace(",", "");
      } else {
        standardised = standardised.replace(".", "").replace(',', '.');
      }
    } else {
      standardised = standardised.replace(',', '.');
    }
    standardised = NON_NUMERIC.matcher(standardised).replaceAll("");
    if (standardised.isEmpty()) {
      return null;
    }
    try {
      return Double.valueOf(standardised);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * How far the price moved since it last moved, as a percentage.
   *
   * <p>Measured against the price before the last change rather than against the last check,
   * so that a price checked hourly and unchanged for a week still shows what it moved by when
   * it did move.
   */
  public Double priceChangePercent() {
    if (price == null || lastPrice == null || lastPrice == 0) {
      return null;
    }
    double percent = Math.round((price - lastPrice) / lastPrice * 100.0 * 10.0) / 10.0;
    return percent == 0 ? null : percent;
  }

  public Map<String, Object> asMap() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("in_stock", inStock);
    out.put("price", price);
    out.put("currency", currency);
    out.put("last_price", lastPrice);
    if (availability != null) {
      out.put("availability", availability);
    }
    return out;
  }

  /** The words a page uses that mean the product can be had. */
  public static boolean meansAvailable(String availability) {
    if (availability == null) {
      return false;
    }
    String lowered = availability.toLowerCase(Locale.ROOT);
    return lowered.contains("instock")
        || lowered.contains("instoreonly")
        || lowered.contains("limitedavailability")
        || lowered.contains("onlineonly")
        || lowered.contains("presale");
  }
}
