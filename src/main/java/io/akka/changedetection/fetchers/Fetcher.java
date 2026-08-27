package io.akka.changedetection.fetchers;

import io.akka.changedetection.processors.Fetched;
import java.util.List;
import java.util.Map;

/** How a page is retrieved. */
public interface Fetcher {

  /** What the caller asks for, and how. */
  final class Request {
    public String url;
    public int timeoutSeconds = 45;
    public Map<String, String> headers = new java.util.LinkedHashMap<>();
    public String body;
    public String method = "GET";
    public boolean ignoreStatusCodes;
    public List<Integer> acceptedStatusCodes = List.of();
    public boolean isBinary;
    public boolean emptyPagesAreAChange;
    public String proxy;
    public List<Map<String, Object>> browserSteps = List.of();
    public Integer waitSeconds;
    public String javascriptToRun;
    public String watchUuid;
    public boolean fetchFavicon = true;
    public String browserConnectionUrl;
    public String screenshotFormat = "jpeg";
    public List<String> includeFilters = List.of();
  }

  /** A name the operator picks the fetcher by. */
  String name();

  /** What the operator sees the fetcher called. */
  String description();

  /** Whether this fetcher can produce a picture of the page. */
  boolean supportsScreenshots();

  /** Whether this fetcher can run the operator's browser steps. */
  boolean supportsBrowserSteps();

  /**
   * Whether this fetcher can report where each element sits on the page.
   *
   * <p>What the visual selector needs: without it the operator can still type a selector, but
   * cannot pick one by clicking the page.
   */
  default boolean supportsElementPositions() {
    return supportsScreenshots();
  }

  Fetched fetch(Request request);
}
