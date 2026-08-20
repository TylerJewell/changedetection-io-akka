package io.akka.changedetection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R9-R11 and R17: turning a fetched body into comparable text. */
class TextPreparationTest {

  @Test
  void htmlIsReducedToTextBeforeAnyRuleRuns() {
    var text =
        TextPreparation.toText(
            "<html><body><p>Price: 10</p><p>In stock</p></body></html>", ContentType.HTML);
    assertThat(text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList())
        .containsExactly("Price: 10", "In stock");
  }

  @Test
  void nonHtmlIsComparedAsReceived() {
    assertThat(TextPreparation.toText("Price: <b>10</b>", ContentType.PLAIN))
        .isEqualTo("Price: <b>10</b>");
  }

  @Test
  void ignoredLinesAreRemovedCaseInsensitivelyAsSubstrings() {
    var text = "Price: 10\nLast Updated: 3pm\nIn stock";
    assertThat(TextPreparation.stripIgnored(text, List.of("last updated")))
        .isEqualTo("Price: 10\nIn stock");
  }

  @Test
  void anEmptyIgnorePatternMatchesNothing() {
    var text = "Price: 10\nIn stock";
    assertThat(TextPreparation.stripIgnored(text, List.of("", "   "))).isEqualTo(text);
  }

  @Test
  void theOrderOfTheRemainingLinesIsTheOrderOfTheInput() {
    var lines = new ArrayList<String>();
    for (int i = 0; i < 1000; i++) {
      lines.add("line" + i);
    }
    lines.set(5, "IGNOREME");
    var expected = lines.stream().filter(l -> !l.equals("IGNOREME")).toList();
    assertThat(
            TextPreparation.stripIgnored(String.join("\n", lines), List.of("IGNOREME"))
                .lines()
                .toList())
        .isEqualTo(expected);
  }

  @Test
  void whitespaceIsRemovedForTheChecksumOnlyWhenAsked() {
    assertThat(TextPreparation.checksum("a b", true))
        .isEqualTo(TextPreparation.checksum("a    b", true));
    assertThat(TextPreparation.checksum("a b", false))
        .isNotEqualTo(TextPreparation.checksum("a    b", false));
  }
}
