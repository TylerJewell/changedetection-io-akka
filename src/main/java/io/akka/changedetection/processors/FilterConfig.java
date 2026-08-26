package io.akka.changedetection.processors;

import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.text.HtmlTools;
import io.akka.changedetection.text.JsonFilter;
import io.akka.changedetection.text.PythonJson;
import io.akka.changedetection.text.PythonText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The rules in force for one check, gathered from the watch, its tags and the global settings.
 *
 * <p>Which of the three a rule comes from is not uniform, and the differences are deliberate:
 * an ignore rule is the union of all three, a subtractive selector is too but in a different
 * order, and a trigger is the union of the watch and its tags only. Flattening them would
 * change what a global setting does.
 */
public final class FilterConfig {

  /** What the check needs to know about the world outside the watch. */
  public interface Context {
    /** The values a tag on this watch supplies for the named field. */
    List<String> tagOverrides(String watchUuid, String attribute);

    /** The global settings tree. */
    Map<String, Object> application();
  }

  private final Watch watch;
  private final Context context;
  private List<String> includeFiltersCache;
  private List<String> subtractiveSelectorsCache;

  public FilterConfig(Watch watch, Context context) {
    this.watch = watch;
    this.context = context;
  }

  private List<String> merged(String attribute, boolean includeGlobal) {
    List<String> rules = new ArrayList<>(watch.fields().strings(attribute));
    rules.addAll(context.tagOverrides(watch.uuid(), attribute));
    List<String> deduplicated = new ArrayList<>(new LinkedHashSet<>(rules));
    if (includeGlobal) {
      Object global = context.application().get("global_" + attribute);
      if (global instanceof List<?> list) {
        for (Object item : list) {
          if (item != null) {
            deduplicated.add(String.valueOf(item));
          }
        }
      }
      deduplicated = new ArrayList<>(new LinkedHashSet<>(deduplicated));
    }
    return deduplicated;
  }

  public List<String> includeFilters() {
    if (includeFiltersCache == null) {
      List<String> filters = merged("include_filters", false);
      if ("accept".equals(watch.fields().string("track_ldjson_price_data", ""))) {
        filters.addAll(HtmlTools.LD_JSON_PRODUCT_OFFER_SELECTORS);
      }
      includeFiltersCache = filters;
    }
    return includeFiltersCache;
  }

  /**
   * Subtractive selectors, tag first and global last.
   *
   * <p>The order is the tag's, then the watch's, then the global one -- not the union order the
   * other rules use, and not deduplicated. Applying the same selector twice removes nothing
   * extra, so the difference only shows in which selector runs first, and a selector that
   * depends on an ancestor its predecessor removed then finds nothing.
   */
  public List<String> subtractiveSelectors() {
    if (subtractiveSelectorsCache == null) {
      List<String> selectors = new ArrayList<>();
      selectors.addAll(context.tagOverrides(watch.uuid(), "subtractive_selectors"));
      selectors.addAll(watch.fields().strings("subtractive_selectors"));
      Object global = context.application().get("global_subtractive_selectors");
      if (global instanceof List<?> list) {
        for (Object item : list) {
          if (item != null) {
            selectors.add(String.valueOf(item));
          }
        }
      }
      subtractiveSelectorsCache = selectors;
    }
    return subtractiveSelectorsCache;
  }

  public List<String> extractLinesContaining() {
    return merged("extract_lines_containing", false);
  }

  public List<String> extractText() {
    return merged("extract_text", false);
  }

  public List<String> ignoreText() {
    return merged("ignore_text", true);
  }

  public List<String> triggerText() {
    return merged("trigger_text", false);
  }

  public List<String> textShouldNotBePresent() {
    return merged("text_should_not_be_present", false);
  }

  public boolean hasIncludeFilters() {
    List<String> filters = includeFilters();
    return !filters.isEmpty() && !filters.get(0).strip().isEmpty();
  }

  public boolean hasIncludeJsonFilters() {
    for (String filter : includeFilters()) {
      for (String prefix : JsonFilter.JSON_FILTER_PREFIXES) {
        if (filter.strip().startsWith(prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  public boolean hasSubtractiveSelectors() {
    List<String> selectors = subtractiveSelectors();
    return !selectors.isEmpty() && !selectors.get(0).strip().isEmpty();
  }

  /**
   * A fingerprint of every rule in force.
   *
   * <p>The check may skip its work when the page has not changed, and that shortcut has to be
   * armed only while the rules are also unchanged. A per-watch edited flag covers the watch's
   * own fields; it does not cover a change to a tag or to a global setting, which would
   * otherwise take effect only the next time the page happened to move.
   */
  public String hash() {
    Map<String, Object> application = context.application();
    var config = PythonJson.MAPPER.createObjectNode();
    config.set("extract_lines_containing", sorted(extractLinesContaining()));
    config.set("extract_text", sorted(extractText()));
    config.set("ignore_text", sorted(ignoreText()));
    config.set("include_filters", sorted(includeFilters()));
    config.set("subtractive_selectors", sorted(subtractiveSelectors()));
    config.set("text_should_not_be_present", sorted(textShouldNotBePresent()));
    config.set("trigger_text", sorted(triggerText()));
    config.put("ignore_whitespace", Fields.truthy(application.get("ignore_whitespace")));
    config.put("strip_ignored_lines", Fields.truthy(application.get("strip_ignored_lines")));

    return PythonText.md5Hex(PythonJson.dumpsSortedCompact(config));
  }

  private static com.fasterxml.jackson.databind.node.ArrayNode sorted(List<String> values) {
    List<String> copy = new ArrayList<>(values);
    copy.sort(java.util.Comparator.naturalOrder());
    var array = PythonJson.MAPPER.createArrayNode();
    for (String value : copy) {
      array.add(value);
    }
    return array;
  }
}
