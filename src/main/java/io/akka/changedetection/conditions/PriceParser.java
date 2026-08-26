package io.akka.changedetection.conditions;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A number pulled out of a page's text, so a condition can be written about it.
 *
 * <p>The hard part is not finding digits, it is deciding which separator is the decimal point.
 * "1,235" is one thousand two hundred and thirty-five in one convention and one point two three
 * five in another, and a watch set to "notify me when it drops below 100" gets the opposite
 * answer depending. The rule used is the same one the original uses: a separator followed by
 * exactly one, two, or four-or-more digits is a decimal point, and one followed by exactly
 * three is a thousands separator.
 */
public final class PriceParser {

  // Whitespace as the source language sees it, which includes the non-breaking space a page
  // writes between a currency symbol and a thousands group. Without that, a price written the
  // way a European site writes it is read as the digit before the space and nothing else.
  private static final Pattern WHITESPACE =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private static final Pattern EURO_AS_SEPARATOR =
      Pattern.compile("[\\d\\s.,']*?\\d\\s*?\u20ac(\\s*?)?\\d(?:\\d|\\d*?)(?:$|[^\\d])");

  private static final Pattern NUMBER =
      Pattern.compile("([.]?\\d[\\d\\s.,']*)\\s*?(?:[^%\\d]|$)");

  private static final Pattern DECIMAL_SEPARATOR =
      Pattern.compile("\\d*([.,\u20ac])(?:\\d{1,2}?|\\d{4}\\d*?)$");

  private PriceParser() {}

  /** The first number in the text that looks like a price, or null when there is none. */
  public static Double parse(String text) {
    String priceText = extractPriceText(text);
    if (priceText == null) {
      return null;
    }
    BigDecimal number = parseNumber(priceText, null);
    return number == null ? null : number.doubleValue();
  }

  static String extractPriceText(String input) {
    if (input == null) {
      return null;
    }
    String price = WHITESPACE.matcher(input).replaceAll(" ");

    if (countOccurrences(price, '\u20ac') == 1) {
      Matcher euro = EURO_AS_SEPARATOR.matcher(price);
      if (euro.find()) {
        return euro.group(0).replace(" ", "");
      }
    }

    Matcher matcher = NUMBER.matcher(price);
    if (matcher.find()) {
      String priceText = stripTrailing(matcher.group(1), ",.");
      priceText = priceText.replace("'", "");
      return countOccurrences(priceText, '.') == 1
          ? priceText.strip()
          : stripLeading(priceText, ",.").strip();
    }
    if (price.toLowerCase(Locale.ROOT).contains("free")) {
      return "0";
    }
    return null;
  }

  static String decimalSeparator(String price) {
    Matcher matcher = DECIMAL_SEPARATOR.matcher(price);
    return matcher.find() ? matcher.group(1) : null;
  }

  static BigDecimal parseNumber(String input, String decimalSeparator) {
    if (input == null || input.isEmpty()) {
      return null;
    }
    String num = input.strip().replace(" ", "");
    String separator = decimalSeparator != null ? decimalSeparator : decimalSeparator(num);
    if (separator == null) {
      num = num.replace(".", "").replace(",", "");
    } else if (separator.equals(".")) {
      num = num.replace(",", "");
    } else if (separator.equals(",")) {
      num = num.replace(".", "").replace(",", ".");
    } else {
      num = num.replace(".", "").replace(",", "").replace("\u20ac", ".");
    }
    try {
      return new BigDecimal(num);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int countOccurrences(String text, char character) {
    int count = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == character) {
        count++;
      }
    }
    return count;
  }

  private static String stripTrailing(String text, String characters) {
    int end = text.length();
    while (end > 0 && characters.indexOf(text.charAt(end - 1)) >= 0) {
      end--;
    }
    return text.substring(0, end);
  }

  private static String stripLeading(String text, String characters) {
    int start = 0;
    while (start < text.length() && characters.indexOf(text.charAt(start)) >= 0) {
      start++;
    }
    return text.substring(start);
  }
}
