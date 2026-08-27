package io.akka.changedetection.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.changedetection.conditions.Levenshtein;
import io.akka.changedetection.conditions.PriceParser;
import io.akka.changedetection.conditions.RuleSet;
import io.akka.changedetection.model.Watch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rules an operator writes about a page rather than about its text.
 *
 * <p>"only when the price is under fifty" and "only when more than a fifth of it moved" are
 * both conditions, and both are decided from facts gathered off the page. The facts are
 * gathered whether or not any rule reads them, so each one is checked here directly as well as
 * through a rule -- a fact that is wrong shows as a rule that is right about the wrong number.
 */
class ConditionsTest {

  private static Watch watchWith(List<Map<String, Object>> rules, String logic) {
    Watch watch = Watch.create("conditions-watch");
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("url", "https://example.com/thing");
    settings.put("conditions", rules);
    settings.put("conditions_match_logic", logic);
    watch.update(settings);
    return watch;
  }

  private static Map<String, Object> rule(String field, String operator, String value) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("field", field);
    out.put("operator", operator);
    out.put("value", value);
    return out;
  }

  @Test
  void aWatchWithNoConditionsAllowsEverything() {
    Watch watch = Watch.create("plain");
    assertTrue(RuleSet.evaluate(watch, "anything at all"));
  }

  @Test
  void aNumberIsPulledOutOfThePagesText() {
    assertEquals(42.0, PriceParser.parse("The price is 42"));
    assertEquals(1234.56, PriceParser.parse("Now only $1,234.56 today"));
    assertEquals(9.99, PriceParser.parse("€9,99"));
  }

  @Test
  void textWithNoNumberInItHasNoNumber() {
    assertNull(PriceParser.parse("nothing numeric here"));
  }

  @Test
  void aRuleAboutTheNumberOnThePageIsDecidedFromIt() {
    Watch watch = watchWith(List.of(rule("extracted_number", "<", "50")), "ALL");
    assertTrue(RuleSet.evaluate(watch, "Price: 42.00"));
    assertFalse(RuleSet.evaluate(watch, "Price: 62.00"));
  }

  @Test
  void aRuleAboutWordCountIsDecidedFromIt() {
    Watch watch = watchWith(List.of(rule("word_count", ">", "3")), "ALL");
    assertTrue(RuleSet.evaluate(watch, "one two three four five"));
    assertFalse(RuleSet.evaluate(watch, "one two"));
  }

  @Test
  void aRuleAboutTheTextItselfIsDecidedFromIt() {
    Watch watch = watchWith(List.of(rule("page_filtered_text", "in", "in stock")), "ALL");
    assertTrue(RuleSet.evaluate(watch, "the item is in stock today"));
    assertFalse(RuleSet.evaluate(watch, "sold out"));
  }

  @Test
  void severalRulesCanBeRequiredTogetherOrSeparately() {
    List<Map<String, Object>> rules =
        List.of(rule("extracted_number", "<", "50"), rule("word_count", ">", "10"));

    Watch all = watchWith(rules, "ALL");
    assertFalse(RuleSet.evaluate(all, "Price: 42.00"), "one holds, the other does not");

    Watch any = watchWith(rules, "ANY");
    assertTrue(RuleSet.evaluate(any, "Price: 42.00"), "one holding is enough");
  }

  @Test
  void everyFactARuleMayBeWrittenAboutIsGathered() {
    Watch watch = Watch.create("facts");
    Map<String, Object> facts = RuleSet.gatherFacts(watch, "The price is 42 pounds today", null);
    assertTrue(facts.containsKey("page_filtered_text"));
    assertTrue(facts.containsKey("word_count"));
    assertTrue(facts.containsKey("extracted_number"));
    assertEquals(42.0, facts.get("extracted_number"));
    assertEquals(6L, facts.get("word_count"));
  }

  @Test
  void aFactThatCannotBeGatheredIsAbsentRatherThanZero() {
    Watch watch = Watch.create("facts");
    Map<String, Object> facts = RuleSet.gatherFacts(watch, "no numbers here", null);
    assertFalse(
        facts.containsKey("extracted_number"),
        "a page with no number has no number, which is not the same as zero");
  }

  @Test
  void aRuleThatCannotBeEvaluatedStopsTheCheckRatherThanAnsweringEitherWay() {
    Watch watch = watchWith(List.of(rule("no_such_fact", "!!!", "?")), "ALL");
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () -> RuleSet.evaluate(watch, "anything"),
        "a rule nobody can decide is reported, not silently answered");
  }

  @Test
  void howFarApartTwoTextsAreIsCountedTheWayTheRuleReadsIt() {
    assertEquals(0, Levenshtein.distance("same", "same"));
    assertEquals(1, Levenshtein.distance("same", "game"));
    assertEquals(4, Levenshtein.distance("", "four"));
    assertEquals(1.0, Levenshtein.ratio("same", "same"));
    assertEquals(1.0, Levenshtein.ratio("", ""), "two empty texts are identical");
  }

  @Test
  void theSimilarityRatioIsTwiceTheMatchOverTheCombinedLength() {
    // "abcd" against "abed": one substitution, so three of four characters match on each side.
    double ratio = Levenshtein.ratio("abcd", "abed");
    assertEquals(0.75, ratio, 0.0001);
  }
}
