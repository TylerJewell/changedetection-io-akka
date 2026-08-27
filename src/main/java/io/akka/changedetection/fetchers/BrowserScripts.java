package io.akka.changedetection.fetchers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * The scripts that run inside the page.
 *
 * <p>These are the original's own, shipped unchanged. What they measure -- which element a
 * selector lands on, where it sits on the page, whether the page says a product is available --
 * is the answer the rest of the system reads, so rewriting them would be rewriting the answer.
 */
public final class BrowserScripts {

  private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

  private BrowserScripts() {}

  /**
   * The element geometry the visual selector draws over, wrapped so it returns a value.
   *
   * <p>The script is written as a function body that ends in a return, which is how the
   * original's driver runs it; evaluated as a bare expression it would be a syntax error.
   */
  public static String xpathElementScraper() {
    return "(function(){" + load("xpath_element_scraper.js") + "})()";
  }

  public static String stockNotInStock() {
    return "(function(){" + load("stock-not-in-stock.js") + "})()";
  }

  public static String faviconFetcher() {
    return "(function(){" + load("favicon-fetcher.js") + "})()";
  }

  public static String lockElementsSizing() {
    return "(function(){" + load("lock-elements-sizing.js") + "})()";
  }

  public static String unlockElementsSizing() {
    return "(function(){" + load("unlock-elements-sizing.js") + "})()";
  }

  static String load(String name) {
    return CACHE.computeIfAbsent(
        name,
        key -> {
          try (InputStream stream =
              BrowserScripts.class.getResourceAsStream("/changedetection/browser/" + key)) {
            if (stream == null) {
              throw new IllegalStateException("browser script " + key + " is not on the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new IllegalStateException("cannot read browser script " + key, e);
          }
        });
  }
}
