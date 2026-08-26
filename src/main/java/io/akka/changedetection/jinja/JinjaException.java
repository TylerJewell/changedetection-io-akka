package io.akka.changedetection.jinja;

/** Raised where the original's template engine raises. */
public class JinjaException extends RuntimeException {

  public JinjaException(String message) {
    super(message);
  }

  public JinjaException(String message, Throwable cause) {
    super(message, cause);
  }
}
