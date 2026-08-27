package io.akka.changedetection.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The shipped wording is readable, and a language the reader asked for is the one they get. */
class TranslationsSmokeTest {

  @Test
  void everyShippedLanguageIsOffered() {
    assertTrue(Translations.codes().contains("de"), "German is shipped");
    assertTrue(Translations.codes().contains("ja"), "Japanese is shipped");
  }

  @Test
  void aPhraseIsTranslated() {
    assertEquals("Gehen", Translations.translate("de", "Go"));
    assertNotEquals("Go", Translations.translate("ja", "Go"));
  }

  @Test
  void anUnknownPhraseFallsBackToWhatWasWritten() {
    assertEquals(
        "a phrase nobody has translated",
        Translations.translate("de", "a phrase nobody has translated"));
  }

  @Test
  void theBrowsersPreferenceIsHonoured() {
    assertEquals("de", Translations.resolve("", "de-DE,de;q=0.9,en;q=0.8"));
    assertEquals("fr", Translations.resolve("", "fr;q=0.9,en;q=0.2"));
  }

  @Test
  void anExplicitChoiceWins() {
    assertEquals("ja", Translations.resolve("ja", "de-DE,de;q=0.9"));
  }
}
