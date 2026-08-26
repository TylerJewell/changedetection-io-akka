package io.akka.changedetection.processors;

import java.util.List;

/**
 * The ways a check can end without a verdict.
 *
 * <p>Each is a distinct outcome the operator sees, not a failure to be swallowed: a filter that
 * matched nothing is reported differently from a page that answered with an error, and only one
 * of them counts towards the consecutive-failure threshold that sends a warning.
 */
public final class ProcessorExceptions {

  private ProcessorExceptions() {}

  /** The fetched page was byte-identical and the rules unchanged, so nothing was reprocessed. */
  public static class ChecksumWasTheSame extends RuntimeException {
    public ChecksumWasTheSame() {
      super("checksum from the previous check was the same");
    }
  }

  /** No configured filter matched, so there was nothing to compare. */
  public static class FilterNotFound extends RuntimeException {
    private final List<String> filters;

    public FilterNotFound(List<String> filters) {
      super(String.valueOf(filters));
      this.filters = filters;
    }

    public List<String> filters() {
      return filters;
    }
  }

  /** The page answered, and what came back had no text in it. */
  public static class ReplyWithContentButNoText extends RuntimeException {
    private final int statusCode;
    private final boolean hasFilters;
    private final String htmlContent;

    public ReplyWithContentButNoText(int statusCode, boolean hasFilters, String htmlContent) {
      super("content but no text");
      this.statusCode = statusCode;
      this.hasFilters = hasFilters;
      this.htmlContent = htmlContent;
    }

    public int statusCode() {
      return statusCode;
    }

    public boolean hasFilters() {
      return hasFilters;
    }

    public String htmlContent() {
      return htmlContent;
    }
  }

  /** The page answered with nothing at all. */
  public static class EmptyReply extends RuntimeException {
    private final int statusCode;

    public EmptyReply(int statusCode) {
      super("empty reply");
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  /** The page answered with a status the watch does not accept. */
  public static class NonSuccessStatus extends RuntimeException {
    private final int statusCode;
    private final String pageText;

    public NonSuccessStatus(int statusCode, String pageText) {
      super("status " + statusCode);
      this.statusCode = statusCode;
      this.pageText = pageText;
    }

    public int statusCode() {
      return statusCode;
    }

    public String pageText() {
      return pageText;
    }
  }

  /** The page could not be reached or read at all. */
  public static class PageUnloadable extends RuntimeException {
    private final int statusCode;

    public PageUnloadable(String message, int statusCode) {
      super(message);
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  /** A processor could not do its job on this page, with a reason for the operator. */
  public static class ProcessorException extends RuntimeException {
    private final int statusCode;

    public ProcessorException(String message, int statusCode) {
      super(message);
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  /** A document conversion tool the deployment does not have. */
  public static class PdfToHtmlToolNotFound extends RuntimeException {
    public PdfToHtmlToolNotFound(String message) {
      super(message);
    }
  }

  /** A browser-driven step could not be run. */
  public static class BrowserStepFailed extends RuntimeException {
    private final int step;

    public BrowserStepFailed(String message, int step) {
      super(message);
      this.step = step;
    }

    public int step() {
      return step;
    }
  }

  /** The chosen fetcher cannot run browser steps at all. */
  public static class BrowserStepsInUnsupportedFetcher extends RuntimeException {
    public BrowserStepsInUnsupportedFetcher() {
      super("browser steps need a browser-driven fetcher");
    }
  }

  /** A browser was configured but could not be reached. */
  public static class BrowserConnectError extends RuntimeException {
    public BrowserConnectError(String message) {
      super(message);
    }
  }

  /** A browser was reached but did not answer in time. */
  public static class BrowserFetchTimedOut extends RuntimeException {
    public BrowserFetchTimedOut(String message) {
      super(message);
    }
  }

  /** A screenshot was asked for and could not be taken. */
  public static class ScreenshotUnavailable extends RuntimeException {
    private final int statusCode;

    public ScreenshotUnavailable(int statusCode) {
      super("screenshot unavailable");
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  /** Script the operator asked to run on the page failed. */
  public static class JsActionException extends RuntimeException {
    private final int statusCode;

    public JsActionException(String message, int statusCode) {
      super(message);
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }
}
