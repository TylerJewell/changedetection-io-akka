package io.akka.changedetection.forms;

import io.akka.changedetection.fetchers.Fetcher;
import io.akka.changedetection.fetchers.Fetchers;
import io.akka.changedetection.model.AppSettings;
import io.akka.changedetection.model.Fields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The lists of options the interface offers, in the order it offers them. */
public final class Choices {

  private Choices() {}

  /**
   * The ways of checking a page, most-used first.
   *
   * <p>The order is the weight the source gives each one and is what makes the first entry the
   * default a new watch gets, so it is not alphabetical by accident.
   */
  public static List<String[]> processors() {
    List<String[]> available = new ArrayList<>();
    available.add(new String[] {"text_json_diff", "Webpage Text/HTML, JSON and PDF changes"});
    available.add(
        new String[] {
          "restock_diff", "Re-stock & Price detection for pages with a SINGLE product"
        });
    available.add(
        new String[] {"image_ssim_diff", "Visual / Image screenshot change detection"});
    List<String> disabled = disabledProcessors();
    available.removeIf(entry -> disabled.contains(entry[0]));
    return available;
  }

  private static List<String> disabledProcessors() {
    String configured = System.getenv("DISABLED_PROCESSORS");
    if (configured == null || configured.isBlank()) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (String name : configured.split(",")) {
      if (!name.strip().isEmpty()) {
        names.add(name.strip());
      }
    }
    return names;
  }

  public static String defaultProcessor() {
    List<String[]> available = processors();
    return available.isEmpty() ? "text_json_diff" : available.get(0)[0];
  }

  /**
   * The short word each kind of watch is badged with in the main list.
   *
   * <p>By name rather than by the order the choices are offered in: the badges are a legend,
   * and a legend that reorders itself as the counts change is harder to read than one that
   * does not.
   */
  public static Map<String, String> processorBadges() {
    Map<String, String> badges = new LinkedHashMap<>();
    badges.put("image_ssim_diff", "Visual");
    badges.put("restock_diff", "Restock");
    badges.put("text_json_diff", "Text");
    List<String> disabled = disabledProcessors();
    badges.keySet().removeAll(disabled);
    return badges;
  }

  /** Every kind of watch, by name, which is the order the generated styling follows. */
  public static List<String> processorNames() {
    List<String> names = new ArrayList<>(processorBadges().keySet());
    return names;
  }

  public static List<String[]> fetchers() {
    List<String[]> available = new ArrayList<>();
    for (Fetcher fetcher : Fetchers.all().values()) {
      available.add(new String[] {fetcher.name(), fetcher.description()});
    }
    return available;
  }

  public static List<String[]> notificationFormats() {
    return asChoices(AppSettings.NOTIFICATION_FORMATS);
  }

  public static List<String[]> rssFormats() {
    return asChoices(AppSettings.rssFormats());
  }

  public static List<String[]> rssTemplateTypes() {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("system_default", "System default");
    types.put("notification_body", "Notification body");
    return asChoices(types);
  }

  public static List<String[]> timeagoFormats() {
    return List.of(
        new String[] {"long", "Long (1 minute ago)"}, new String[] {"short", "Short (1m ago)"});
  }

  public static List<String[]> sidebarModes() {
    return List.of(
        new String[] {"collapsed", "Collapsed icon rail (expands on hover)"},
        new String[] {"pinned", "Always expanded"});
  }

  /**
   * The comparisons a rule may use.
   *
   * <p>The first entry has no value, which is how a row that the operator has not filled in yet
   * is told apart from one that means "equals".
   */
  public static List<String[]> conditionOperators() {
    return List.of(
        new String[] {"None", "Choose one - Operator"},
        new String[] {">", "Greater Than"},
        new String[] {"<", "Less Than"},
        new String[] {">=", "Greater Than or Equal To"},
        new String[] {"<=", "Less Than or Equal To"},
        new String[] {"==", "Equals"},
        new String[] {"!=", "Not Equals"},
        new String[] {"in", "Contains"},
        new String[] {"!in", "Does NOT Contain"},
        new String[] {"starts_with", "Text Starts With"},
        new String[] {"ends_with", "Text Ends With"},
        new String[] {"length_min", "Length minimum"},
        new String[] {"length_max", "Length maximum"},
        new String[] {"contains_regex", "Text Matches Regex"},
        new String[] {"!contains_regex", "Text Does NOT Match Regex"});
  }

  /**
   * The facts a condition may be written about, in the order the page offers them.
   *
   * <p>Three of them come from what the original packages as plugins, and they are offered
   * before the two built-in ones because that is the order plugin registration produces.
   */
  public static List<String[]> conditionFields() {
    return List.of(
        new String[] {"None", "Choose one - Field"},
        new String[] {"levenshtein_ratio", "Levenshtein - Text similarity ratio"},
        new String[] {"levenshtein_distance", "Levenshtein - Text change distance"},
        new String[] {"word_count", "Word count of content"},
        new String[] {"extracted_number", "Extracted number after 'Filters & Triggers'"},
        new String[] {"page_filtered_text", "Page text after 'Filters & Triggers'"});
  }

  /**
   * What a browser step can do, with two flags telling the page which of its two inputs apply.
   *
   * <p>The flags are read by the interface's own script, so they travel with the option rather
   * than being worked out again in the page.
   */
  public static Map<String, String> browserStepConfig() {
    Map<String, String> config = new LinkedHashMap<>();
    config.put("Choose one", "0 0");
    config.put("Check checkbox", "1 0");
    config.put("Click X,Y", "0 1");
    config.put("Click element if exists", "1 0");
    config.put("Click element", "1 0");
    config.put("Click element containing text", "0 1");
    config.put("Click element containing text if exists", "0 1");
    config.put("Enter text in field", "1 1");
    config.put("Execute JS", "0 1");
    config.put("Goto site", "0 0");
    config.put("Goto URL", "0 1");
    config.put("Make all child elements visible", "1 0");
    config.put("Press Enter", "0 0");
    config.put("Select by label", "1 1");
    config.put("<select> by option text", "1 1");
    config.put("Scroll down", "0 0");
    config.put("Uncheck checkbox", "1 0");
    config.put("Wait for seconds", "0 1");
    config.put("Wait for text", "0 1");
    config.put("Wait for text in element", "1 1");
    config.put("Remove elements", "1 0");
    return config;
  }

  public static List<String[]> browserStepOperations() {
    List<String[]> operations = new ArrayList<>();
    for (String operation : browserStepConfig().keySet()) {
      operations.add(new String[] {operation, operation});
    }
    return operations;
  }

  public static List<String[]> requestMethods() {
    // A set in the original, so the order the page shows is not fixed there; fixed here to the
    // order the methods are written in, which is the order a reader expects.
    return List.of(
        new String[] {"GET", "GET"},
        new String[] {"POST", "POST"},
        new String[] {"PUT", "PUT"},
        new String[] {"PATCH", "PATCH"},
        new String[] {"DELETE", "DELETE"},
        new String[] {"OPTIONS", "OPTIONS"});
  }

  public static List<String[]> restockProcessing() {
    return List.of(
        new String[] {"in_stock_only", "In Stock only (Out Of Stock -> In Stock only)"},
        new String[] {"all_changes", "Any availability changes"},
        new String[] {"off", "Off, don't follow availability/restock"});
  }

  public static List<String[]> screenshotSensitivity() {
    return List.of(
        new String[] {"", "Use global default"},
        new String[] {"200", "Low sensitivity (only major changes)"},
        new String[] {"80", "Medium sensitivity (moderate changes - recommended)"},
        new String[] {"20", "High sensitivity (small changes)"},
        new String[] {"0", "Very high sensitivity (any change)"});
  }

  public static List<String[]> promptModes() {
    return List.of(
        new String[] {"replace", "Replace the inherited prompt"},
        new String[] {"append", "Append to the inherited prompt"});
  }

  public static List<String[]> thinkingBudgets() {
    return List.of(
        new String[] {"0", "Off (no thinking)"},
        new String[] {"100", "100"},
        new String[] {"500", "500"},
        new String[] {"2000", "2000"});
  }

  public static List<String[]> summaryTokenCaps() {
    return List.of(
        new String[] {"500", "500"},
        new String[] {"1000", "1000"},
        new String[] {"3000", "3000"},
        new String[] {"5000", "5000"},
        new String[] {"10000", "10000"},
        new String[] {"15000", "15000"});
  }

  public static List<String[]> budgetActions() {
    return List.of(
        new String[] {"skip_llm", "Skip AI summarisation only (watch still checks)"},
        new String[] {"skip_check", "Skip the watch check entirely"});
  }

  public static List<String[]> overviewSummaryBaselines() {
    return List.of(
        new String[] {"second_last_version", "Previous version (second-last vs latest)"},
        new String[] {"since_last_viewed", "Changes since you last viewed the watch"});
  }

  public static List<String[]> importMappings() {
    return List.of(
        new String[] {"wachete", "Wachete mapping"}, new String[] {"custom", "Custom mapping"});
  }

  public static List<String[]> hoursOfDay() {
    List<String[]> hours = new ArrayList<>();
    for (int hour = 0; hour <= 24; hour++) {
      hours.add(new String[] {String.valueOf(hour), String.valueOf(hour)});
    }
    return hours;
  }

  public static List<String[]> minutesOfHour() {
    List<String[]> minutes = new ArrayList<>();
    for (int minute = 0; minute < 60; minute++) {
      minutes.add(new String[] {String.valueOf(minute), String.valueOf(minute)});
    }
    return minutes;
  }

  public static final List<String> DAYS_OF_WEEK =
      List.of(
          "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

  public static String dayLabel(String day) {
    return day.substring(0, 1).toUpperCase(Locale.ROOT) + day.substring(1);
  }

  /**
   * The proxies a watch may be sent through, keyed the way the stored settings key them.
   *
   * <p>Empty when none are configured, which is what the interface reads as "do not offer the
   * choice at all" rather than as "offer an empty list".
   */
  public static Map<String, Map<String, String>> proxies(
      Map<String, Object> settings, Map<String, Map<String, String>> fromFile) {
    Map<String, Map<String, String>> available = new LinkedHashMap<>();
    if (fromFile != null) {
      available.putAll(fromFile);
    }
    Object requests = settings == null ? null : settings.get("requests");
    if (requests instanceof Map<?, ?> map && map.get("extra_proxies") instanceof List<?> extras) {
      for (Object extra : extras) {
        if (!(extra instanceof Map<?, ?> proxy)) {
          continue;
        }
        Object proxyName = proxy.get("proxy_name");
        Object proxyUrl = proxy.get("proxy_url");
        if (proxyName == null || String.valueOf(proxyName).isBlank()) {
          continue;
        }
        if (proxyUrl == null || String.valueOf(proxyUrl).isBlank()) {
          continue;
        }
        // The position is part of the key in name only -- the original never advances it, so
        // two proxies with the same name collapse into one entry, and a watch that named the
        // second still resolves to the first rather than to nothing.
        String key = "ui-0" + proxyName;
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("label", String.valueOf(proxyName));
        entry.put("url", String.valueOf(proxyUrl));
        available.put(key, entry);
      }
    }
    if (!available.isEmpty() && enabled("ENABLE_NO_PROXY_OPTION", true)) {
      Map<String, String> none = new LinkedHashMap<>();
      none.put("label", "No proxy");
      none.put("url", "");
      available.put("no-proxy", none);
    }
    return available;
  }

  private static boolean enabled(String variable, boolean fallback) {
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String lower = value.strip().toLowerCase(Locale.ROOT);
    return lower.equals("y")
        || lower.equals("yes")
        || lower.equals("t")
        || lower.equals("true")
        || lower.equals("on")
        || lower.equals("1");
  }

  static List<String[]> asChoices(Map<String, String> values) {
    List<String[]> choices = new ArrayList<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      choices.add(new String[] {entry.getKey(), entry.getValue()});
    }
    return choices;
  }

  /** The watch field name that means "take the system's notification format". */
  public static String systemDefaultNotificationFormat() {
    return Fields.USE_SYSTEM_DEFAULT_NOTIFICATION_FORMAT;
  }

  /** Every timezone the scheduler will accept, which the page offers as a datalist. */
  public static List<String> timezones() {
    List<String> zones = new ArrayList<>(java.time.ZoneId.getAvailableZoneIds());
    zones.sort(String::compareTo);
    return zones;
  }

  public static boolean isKnownTimezone(String name) {
    return name != null && !name.isBlank() && java.time.ZoneId.getAvailableZoneIds().contains(name);
  }

  static List<String> asList(String... values) {
    return Arrays.asList(values);
  }
}
