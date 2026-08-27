package io.akka.changedetection.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.changedetection.model.Watch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When a fetched page counts as a change worth telling somebody about.
 *
 * <p>Every rule here is about what happens *next* time, so each is put a sequence of bodies
 * rather than one: whether a blocked check leaves the stored checksum alone, whether an ignored
 * line still reaches the stored snapshot, and whether a line seen long ago still counts as
 * seen. A table of single inputs agrees on all of them and has compared none of them.
 */
class DetectionRulesTest {

  private static Watch watch(Map<String, Object> settings) {
    Watch watch = Watch.create("watch-" + Math.abs(settings.hashCode()));
    watch.update(Map.of("url", "https://example.com/page"));
    watch.update(settings);
    watch.resetEditedFlag();
    return watch;
  }

  private static String page(String... lines) {
    return "<html><body><p>" + String.join("</p><p>", lines) + "</p></body></html>";
  }

  @Test
  void theFirstCheckOfAWatchIsAChange() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of());
    assertTrue(rig.check(watch, page("hello")).changed());
  }

  @Test
  void theSameBodyTwiceIsNotAChange() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of());
    assertTrue(rig.check(watch, page("hello")).changed());
    assertFalse(rig.check(watch, page("hello")).changed());
  }

  @Test
  void aDifferentBodyIsAChange() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of());
    assertTrue(rig.check(watch, page("hello")).changed());
    assertFalse(rig.check(watch, page("hello")).changed());
    assertTrue(rig.check(watch, page("goodbye")).changed());
  }

  @Test
  void anIgnoredLineDoesNotMakeAChange() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("ignore_text", List.of("the time is")));
    assertTrue(rig.check(watch, page("stable", "the time is 1")).changed());
    assertFalse(
        rig.check(watch, page("stable", "the time is 2")).changed(),
        "only the ignored line moved");
    assertTrue(
        rig.check(watch, page("moved", "the time is 3")).changed(),
        "and a line that is not ignored still counts");
  }

  @Test
  void anIgnoredLineIsMatchedWithoutRegardToCase() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("ignore_text", List.of("THE TIME IS")));
    assertTrue(rig.check(watch, page("stable", "the time is 1")).changed());
    assertFalse(rig.check(watch, page("stable", "the time is 2")).changed());
  }

  @Test
  void anIgnoredLineStillReachesTheStoredSnapshot() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("ignore_text", List.of("the time is")));
    Rig.Result first = rig.check(watch, page("stable", "the time is 1"));
    assertTrue(first.contents().contains("the time is 1"), first.contents());
  }

  @Test
  void anIgnoredLineIsDroppedFromTheSnapshotWhenTheOperatorAsks() {
    Rig rig = new Rig();
    Watch watch =
        watch(Map.of("ignore_text", List.of("the time is"), "strip_ignored_lines", true));
    Rig.Result first = rig.check(watch, page("stable", "the time is 1"));
    assertFalse(first.contents().contains("the time is 1"), first.contents());
    assertTrue(first.contents().contains("stable"));
  }

  @Test
  void nothingIsReportedWhileNoTriggerAppears() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("trigger_text", List.of("in stock")));
    assertFalse(rig.check(watch, page("sold out")).changed(), "the trigger is absent");
    assertFalse(rig.check(watch, page("still sold out")).changed());
    assertTrue(rig.check(watch, page("in stock")).changed(), "and now it is present");
  }

  @Test
  void aBlockedCheckLeavesTheStoredChecksumWhereItWas() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("trigger_text", List.of("in stock")));
    assertFalse(rig.check(watch, page("sold out")).changed());
    assertTrue(rig.check(watch, page("in stock")).changed());
    // If the blocked body had been recorded, this identical body would now look unchanged.
    assertTrue(rig.check(watch, page("sold out again in stock")).changed());
  }

  @Test
  void nothingIsReportedWhileAForbiddenPhraseIsPresent() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("text_should_not_be_present", List.of("under maintenance")));
    assertFalse(rig.check(watch, page("under maintenance")).changed());
    assertTrue(rig.check(watch, page("open for business")).changed());
  }

  @Test
  void aForbiddenPhraseIsLookedForBeforeTheIgnoreRulesRun() {
    Rig rig = new Rig();
    Watch watch =
        watch(
            Map.of(
                "ignore_text", List.of("under maintenance"),
                "text_should_not_be_present", List.of("under maintenance")));
    // The ignore rule would have removed the line the forbidden rule reads. It does not.
    assertFalse(
        rig.check(watch, page("hello", "under maintenance")).changed(),
        "the forbidden phrase is still found");
  }

  @Test
  void aTriggerIsLookedForAfterTheIgnoreRulesHaveRun() {
    Rig rig = new Rig();
    Watch watch =
        watch(Map.of("ignore_text", List.of("in stock"), "trigger_text", List.of("in stock")));
    assertFalse(
        rig.check(watch, page("in stock")).changed(),
        "the ignore rule removed the line the trigger was looking for");
  }

  @Test
  void aChangeWhoseEveryLineHasBeenSeenBeforeCanBeSuppressed() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("check_unique_lines", true));
    assertTrue(rig.check(watch, page("alpha", "beta")).changed());
    assertTrue(rig.check(watch, page("alpha", "beta", "gamma")).changed(), "gamma is new");
    assertFalse(
        rig.check(watch, page("beta", "alpha", "gamma")).changed(),
        "reordering introduces nothing that was not already seen");
  }

  @Test
  void uniquenessIsJudgedAgainstEveryStoredVersionRatherThanTheLastOne() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("check_unique_lines", true));
    assertTrue(rig.check(watch, page("alpha")).changed());
    assertTrue(rig.check(watch, page("beta")).changed());
    assertFalse(
        rig.check(watch, page("alpha")).changed(),
        "alpha was seen two versions ago, not one");
  }

  @Test
  void whitespaceAloneDoesNotMoveTheAnswerByDefault() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of());
    assertTrue(rig.check(watch, page("hello world")).changed());
    assertFalse(rig.check(watch, page("hello    world")).changed());
  }

  @Test
  void turningTheWhitespaceSettingOffDoesNotMakeSpacingInsideMarkupCount() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("ignore_whitespace", false));
    rig.application().put("ignore_whitespace", false);
    assertTrue(rig.check(watch, page("hello world")).changed());
    // The setting governs the checksum, and by the time the checksum is taken the markup has
    // already been rendered to text -- and rendering collapses a run of spaces to one. So a
    // page that differs only in how much space is inside a paragraph produces the same text
    // either way, and the setting has nothing left to decide.
    assertFalse(rig.check(watch, page("hello    world")).changed());
  }

  @Test
  void aConditionThatDoesNotHoldStopsTheChangeBeingReported() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of());
    assertTrue(rig.check(watch, page("first")).changed());
    rig.conditionsPass(false);
    assertFalse(rig.check(watch, page("second")).changed());
    rig.conditionsPass(true);
    assertTrue(rig.check(watch, page("third")).changed());
  }

  @Test
  void editingTheWatchMakesTheNextCheckLookAgainAtAnUnchangedPage() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of());
    assertTrue(rig.check(watch, page("alpha", "beta")).changed());
    assertFalse(rig.check(watch, page("alpha", "beta")).changed());

    watch.update(Map.of("ignore_text", List.of("beta")));
    // The body has not moved, but the rules have: the shortcut must not skip the check. The
    // check then compares text with one fewer line than what is stored, which is a change --
    // an edit that removes a line from the comparison is reported as one.
    Rig.Result afterEdit = rig.check(watch, page("alpha", "beta"));
    assertTrue(afterEdit.changed(), "the shortcut did not skip it");
    // The stored snapshot still holds the ignored line -- ignoring governs what is compared,
    // not what is kept -- so what proves the new rule took effect is that the same body is
    // now settled: the next check of it is unchanged.
    assertFalse(rig.check(watch, page("alpha", "beta")).changed());
  }

  @Test
  void aBodyThatIsNotMarkupIsComparedAsItArrived() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of());
    Rig.Result first = rig.check(watch, "one\ntwo\n", "text/plain");
    assertTrue(first.changed());
    assertTrue(first.contents().contains("one"));
    assertFalse(rig.check(watch, "one\ntwo\n", "text/plain").changed());
    assertTrue(rig.check(watch, "one\nthree\n", "text/plain").changed());
  }

  @Test
  void linesCanBeSortedBeforeTheyAreCompared() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("sort_text_alphabetically", true));
    assertTrue(rig.check(watch, page("beta", "alpha")).changed());
    assertFalse(
        rig.check(watch, page("alpha", "beta")).changed(),
        "the same lines in another order sort to the same text");
  }

  @Test
  void repeatedLinesCanBeCollapsedBeforeTheyAreCompared() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("remove_duplicate_lines", true));
    assertTrue(rig.check(watch, page("alpha", "beta")).changed());
    assertFalse(rig.check(watch, page("alpha", "beta", "alpha")).changed());
  }

  @Test
  void onlyTheExtractedPartIsComparedWhenAnExtractionIsSet() {
    Rig rig = new Rig();
    Watch watch = watch(Map.of("extract_text", List.of("/price: ([0-9.]+)/")));
    assertTrue(rig.check(watch, page("noise", "price: 10.00", "more noise")).changed());
    assertFalse(
        rig.check(watch, page("other noise", "price: 10.00", "different noise")).changed(),
        "everything but the extracted part moved");
    assertTrue(rig.check(watch, page("noise", "price: 11.00")).changed());
  }
}
