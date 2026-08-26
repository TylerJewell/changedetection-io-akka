package io.akka.changedetection.conditions;

/**
 * How far apart two snapshots are, so a condition can ask about the size of a change.
 *
 * <p>The similarity measure is not the obvious one. It counts matching characters the way the
 * distance's own accounting does -- twice the matches over the combined length -- rather than
 * one minus the distance over the longer string, and the two disagree whenever the edits are
 * substitutions rather than insertions. A watch set to "tell me when the page changed by more
 * than five per cent" would sit on the wrong side of its threshold.
 */
public final class Levenshtein {

  /** Beyond this the measure is not computed, because it costs the product of the lengths. */
  public static final int MAX_LENGTH_FOR_EDIT_STATS = 100000;

  private Levenshtein() {}

  public static int distance(String a, String b) {
    if (a == null) {
      a = "";
    }
    if (b == null) {
      b = "";
    }
    int n = b.length();
    int[] previous = new int[n + 1];
    int[] current = new int[n + 1];
    for (int j = 0; j <= n; j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      char ai = a.charAt(i - 1);
      for (int j = 1; j <= n; j++) {
        int cost = ai == b.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[n];
  }

  /**
   * The similarity ratio, on the same definition the original's library uses: one minus the
   * distance measured with a substitution costing two, over the combined length.
   */
  public static double ratio(String a, String b) {
    if (a == null) {
      a = "";
    }
    if (b == null) {
      b = "";
    }
    int total = a.length() + b.length();
    if (total == 0) {
      return 1.0;
    }
    int weighted = weightedDistance(a, b);
    return (total - weighted) / (double) total;
  }

  private static int weightedDistance(String a, String b) {
    int n = b.length();
    int[] previous = new int[n + 1];
    int[] current = new int[n + 1];
    for (int j = 0; j <= n; j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      char ai = a.charAt(i - 1);
      for (int j = 1; j <= n; j++) {
        int substitution = ai == b.charAt(j - 1) ? previous[j - 1] : previous[j - 1] + 2;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[n];
  }
}
