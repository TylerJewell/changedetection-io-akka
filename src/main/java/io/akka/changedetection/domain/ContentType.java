package io.akka.changedetection.domain;

/** What kind of body came back, to the only resolution the rules in SPEC-001 §3 need. */
public enum ContentType {
  HTML,
  PLAIN;

  public static ContentType fromHeader(String contentTypeHeader) {
    if (contentTypeHeader == null) {
      // A server that says nothing is treated as HTML, matching the source's default.
      return HTML;
    }
    return contentTypeHeader.toLowerCase().contains("html") ? HTML : PLAIN;
  }
}
