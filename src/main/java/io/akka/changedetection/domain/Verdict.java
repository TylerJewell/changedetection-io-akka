package io.akka.changedetection.domain;

/** What a single check concluded. SPEC-001 §3 R8, R12, R13, R15, R18. */
public enum Verdict {
  /** The comparable text differs from the last one that was allowed to be recorded. */
  CHANGED,
  /** It does not. */
  UNCHANGED,
  /** The raw body was byte-identical and no rule was consulted (R8). */
  UNCHANGED_RAW_IDENTICAL,
  /** The text changed, but every line of it had been seen before (R18). */
  UNCHANGED_NO_UNIQUE_LINES,
  /** A trigger is configured and none of its entries is present (R12). */
  BLOCKED_NO_TRIGGER,
  /** Forbidden text is present (R13). */
  BLOCKED_FORBIDDEN;

  public boolean isChange() {
    return this == CHANGED;
  }

  public boolean isBlocked() {
    return this == BLOCKED_NO_TRIGGER || this == BLOCKED_FORBIDDEN;
  }
}
