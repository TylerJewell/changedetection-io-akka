package io.akka.changedetection.fetchers;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every way a page can be fetched, by the name the operator chooses. */
public final class Fetchers {

  private static final Map<String, Fetcher> BY_NAME = new LinkedHashMap<>();

  static {
    register(new HttpFetcher());
    register(BrowserFetcher.playwright());
    register(new WebDriverFetcher());
  }

  private Fetchers() {}

  public static void register(Fetcher fetcher) {
    BY_NAME.put(fetcher.name(), fetcher);
  }

  public static Map<String, Fetcher> all() {
    return BY_NAME;
  }

  public static Fetcher byName(String name) {
    return BY_NAME.get(name);
  }

  /**
   * The fetcher a watch should use, with the two rules that override its own choice.
   *
   * <p>A watch set to follow the system default takes whatever the settings say. A document is
   * always fetched plainly whatever the watch says, because a browser would show a viewer
   * rather than text.
   */
  public static Fetcher resolve(String watchChoice, String systemDefault, boolean isPdf) {
    if (isPdf) {
      return BY_NAME.get("html_requests");
    }
    String chosen =
        watchChoice == null || watchChoice.isBlank() || watchChoice.equals("system")
            ? systemDefault
            : watchChoice;
    Fetcher fetcher = BY_NAME.get(chosen);
    if (fetcher != null) {
      return fetcher;
    }
    // An extra browser is configured by name in the settings and shares the browser fetcher's
    // behaviour, differing only in which browser it connects to.
    if (chosen != null && chosen.startsWith("extra_browser_")) {
      return BY_NAME.get("html_webdriver");
    }
    return BY_NAME.get("html_requests");
  }
}
