package io.akka.changedetection.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The interface's own wording, in the language the reader asked for.
 *
 * <p>Read from the shipped catalogues rather than compiled in, so that the wording stays the
 * translators' work and a language can be corrected without touching the code. A phrase with no
 * translation falls back to the English it was written in, which is what a missing catalogue,
 * an unfinished language, and a newly added phrase all look like.
 */
public final class Translations {

  /** The flag and the name each language is offered under. */
  public static final Map<String, Map<String, String>> LANGUAGE_DATA = new LinkedHashMap<>();

  static {
    put("en_GB", "fi fi-gb fis", "English (UK)");
    put("en_US", "fi fi-us fis", "English (US)");
    put("de", "fi fi-de fis", "Deutsch");
    put("fr", "fi fi-fr fis", "Français");
    put("ko", "fi fi-kr fis", "한국어");
    put("cs", "fi fi-cz fis", "Čeština");
    put("es", "fi fi-es fis", "Español");
    put("pt", "fi fi-pt fis", "Português (Portugal)");
    put("pt_BR", "fi fi-br fis", "Português (Brasil)");
    put("it", "fi fi-it fis", "Italiano");
    put("ja", "fi fi-jp fis", "日本語");
    put("zh", "fi fi-cn fis", "中文 (简体)");
    put("zh_Hant_TW", "fi fi-tw fis", "繁體中文");
    put("ru", "fi fi-ru fis", "Русский");
    put("pl", "fi fi-pl fis", "Polski");
    put("nl", "fi fi-nl fis", "Nederlands");
    put("sv", "fi fi-se fis", "Svenska");
    put("da", "fi fi-dk fis", "Dansk");
    put("no", "fi fi-no fis", "Norsk");
    put("fi", "fi fi-fi fis", "Suomi");
    put("tr", "fi fi-tr fis", "Türkçe");
    put("ar", "fi fi-sa fis", "العربية");
    put("hi", "fi fi-in fis", "हिन्दी");
    put("uk", "fi fi-ua fis", "Українська");
  }

  /** What a browser calls a language, against what the catalogues are filed under. */
  private static final Map<String, String> ALIASES =
      Map.of("zh-TW", "zh_Hant_TW", "zh_TW", "zh_Hant_TW");

  private static final Map<String, Map<String, String>> CATALOGUES = new ConcurrentHashMap<>();

  /** What gettext puts between a context and the phrase it disambiguates. */
  private static final String SEPARATOR = String.valueOf((char) 0x04);

  private static final List<String> AVAILABLE = discover();

  private Translations() {}

  private static void put(String code, String flag, String name) {
    Map<String, String> data = new LinkedHashMap<>();
    data.put("flag", flag);
    data.put("name", name);
    LANGUAGE_DATA.put(code, data);
  }

  private static List<String> discover() {
    List<String> found = new ArrayList<>();
    for (String code : LANGUAGE_DATA.keySet()) {
      if (Translations.class.getResource(path(code)) != null) {
        found.add(code);
      }
    }
    if (!found.contains("en_GB") && !found.contains("en_US")) {
      found.add("en_GB");
    }
    return found;
  }

  private static String path(String code) {
    return "/changedetection/translations/" + code + "/messages.po";
  }

  /** The languages the interface offers, with what to show for each. */
  public static Map<String, Map<String, String>> available() {
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    for (String code : AVAILABLE) {
      out.put(code, LANGUAGE_DATA.get(code));
    }
    return out;
  }

  public static List<String> codes() {
    return new ArrayList<>(AVAILABLE);
  }

  public static String flagFor(String locale) {
    Map<String, String> data = LANGUAGE_DATA.get(locale);
    return data == null ? "🌐" : data.get("flag");
  }

  public static String nameFor(String locale) {
    Map<String, String> data = LANGUAGE_DATA.get(locale);
    return data == null ? locale.toUpperCase(Locale.ROOT) : data.get("name");
  }

  /**
   * The language to use, from what the reader chose or, failing that, what they asked for.
   *
   * <p>The header is a ranked list, and the best match against what is available is taken --
   * a reader who prefers Brazilian Portuguese but would accept Portuguese should get one of
   * them rather than English.
   */
  public static String resolve(String chosen, String acceptLanguage) {
    if (chosen != null && !chosen.isEmpty()) {
      return chosen;
    }
    String best = bestMatch(acceptLanguage);
    return ALIASES.getOrDefault(best, best);
  }

  static String bestMatch(String acceptLanguage) {
    if (acceptLanguage == null || acceptLanguage.isBlank()) {
      return "";
    }
    List<String> candidates = new ArrayList<>(AVAILABLE);
    candidates.addAll(ALIASES.keySet());

    String bestCode = "";
    double bestQuality = -1;
    for (String part : acceptLanguage.split(",")) {
      String[] pieces = part.strip().split(";");
      String tag = pieces[0].strip();
      double quality = 1.0;
      for (int index = 1; index < pieces.length; index++) {
        String piece = pieces[index].strip();
        if (piece.startsWith("q=")) {
          try {
            quality = Double.parseDouble(piece.substring(2));
          } catch (NumberFormatException e) {
            quality = 1.0;
          }
        }
      }
      if (quality <= bestQuality) {
        continue;
      }
      String match = matchTag(tag, candidates);
      if (!match.isEmpty()) {
        bestCode = match;
        bestQuality = quality;
      }
    }
    return bestCode;
  }

  private static String matchTag(String tag, List<String> candidates) {
    String normalised = tag.replace('-', '_');
    for (String candidate : candidates) {
      if (candidate.equalsIgnoreCase(normalised)) {
        return candidate;
      }
    }
    String language = normalised.contains("_")
        ? normalised.substring(0, normalised.indexOf('_'))
        : normalised;
    for (String candidate : candidates) {
      if (candidate.equalsIgnoreCase(language)) {
        return candidate;
      }
    }
    for (String candidate : candidates) {
      if (candidate.toLowerCase(Locale.ROOT).startsWith(language.toLowerCase(Locale.ROOT) + "_")) {
        return candidate;
      }
    }
    return "";
  }

  /** The phrase in the reader's language, or the phrase as written. */
  public static String translate(String locale, String message) {
    if (locale == null || locale.isEmpty()) {
      return message;
    }
    Map<String, String> catalogue = catalogue(locale);
    String translated = catalogue.get(message);
    return translated == null || translated.isEmpty() ? message : translated;
  }

  /** The phrase in a named context, which is how two identical words are told apart. */
  public static String translate(String locale, String context, String message) {
    if (locale == null || locale.isEmpty()) {
      return message;
    }
    String translated = catalogue(locale).get(context + SEPARATOR + message);
    return translated == null || translated.isEmpty() ? message : translated;
  }

  static Map<String, String> catalogue(String locale) {
    return CATALOGUES.computeIfAbsent(locale, Translations::load);
  }

  /**
   * One catalogue, read from the shipped file.
   *
   * <p>The text form is read rather than the compiled one because it is the form the
   * translators produce and review, and reading it here means the shipped catalogue and the
   * reviewed one cannot drift apart.
   */
  private static Map<String, String> load(String locale) {
    Map<String, String> catalogue = new LinkedHashMap<>();
    try (InputStream stream = Translations.class.getResourceAsStream(path(locale))) {
      if (stream == null) {
        return catalogue;
      }
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));

      StringBuilder context = null;
      StringBuilder id = null;
      StringBuilder text = null;
      // Which of the three the continuation lines belong to.
      StringBuilder current = null;

      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          store(catalogue, context, id, text);
          context = null;
          id = null;
          text = null;
          current = null;
          continue;
        }
        if (trimmed.startsWith("msgctxt ")) {
          store(catalogue, context, id, text);
          id = null;
          text = null;
          context = new StringBuilder(unquote(trimmed.substring("msgctxt ".length())));
          current = context;
          continue;
        }
        if (trimmed.startsWith("msgid_plural ")) {
          // Only the singular form is used by the interface; the plural is read past.
          current = new StringBuilder();
          continue;
        }
        if (trimmed.startsWith("msgid ")) {
          if (id != null) {
            store(catalogue, context, id, text);
            context = null;
            text = null;
          }
          id = new StringBuilder(unquote(trimmed.substring("msgid ".length())));
          current = id;
          continue;
        }
        if (trimmed.startsWith("msgstr[0] ")) {
          text = new StringBuilder(unquote(trimmed.substring("msgstr[0] ".length())));
          current = text;
          continue;
        }
        if (trimmed.startsWith("msgstr[")) {
          current = new StringBuilder();
          continue;
        }
        if (trimmed.startsWith("msgstr ")) {
          text = new StringBuilder(unquote(trimmed.substring("msgstr ".length())));
          current = text;
          continue;
        }
        if (trimmed.startsWith("\"") && current != null) {
          current.append(unquote(trimmed));
        }
      }
      store(catalogue, context, id, text);
    } catch (IOException e) {
      return catalogue;
    }
    return catalogue;
  }

  private static void store(
      Map<String, String> catalogue, StringBuilder context, StringBuilder id, StringBuilder text) {
    if (id == null || text == null || id.length() == 0 || text.length() == 0) {
      return;
    }
    String key = context == null ? id.toString() : context + SEPARATOR + id;
    catalogue.put(key, text.toString());
  }

  /** One quoted string from the catalogue, with its escapes resolved. */
  static String unquote(String quoted) {
    String value = quoted.strip();
    if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
      return value;
    }
    String inner = value.substring(1, value.length() - 1);
    StringBuilder sb = new StringBuilder();
    for (int index = 0; index < inner.length(); index++) {
      char c = inner.charAt(index);
      if (c != '\\' || index + 1 >= inner.length()) {
        sb.append(c);
        continue;
      }
      char next = inner.charAt(++index);
      switch (next) {
        case 'n' -> sb.append('\n');
        case 't' -> sb.append('\t');
        case 'r' -> sb.append('\r');
        case '\\' -> sb.append('\\');
        case '"' -> sb.append('"');
        default -> sb.append(next);
      }
    }
    return sb.toString();
  }
}
