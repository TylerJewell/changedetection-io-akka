package io.akka.changedetection.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The difference between two snapshots, line by line. SPEC-001 §3 R20-R22.
 *
 * <p>Runs of lines are classified as removed, added, or replaced; unchanged runs are dropped. A
 * caller that asks for only some categories and gets nothing back has been told the two snapshots
 * agree in the categories it asked about.
 */
public final class Diff {

  public enum Kind {
    REMOVED,
    ADDED,
    REPLACED
  }

  public record Chunk(Kind kind, List<String> before, List<String> after) {}

  private Diff() {}

  public static List<Chunk> chunks(String before, String after) {
    List<String> a = lines(before);
    List<String> b = lines(after);
    List<Chunk> out = new ArrayList<>();
    for (int[] op : opcodes(a, b)) {
      int alo = op[0];
      int ahi = op[1];
      int blo = op[2];
      int bhi = op[3];
      List<String> removed = a.subList(alo, ahi);
      List<String> added = b.subList(blo, bhi);
      if (removed.isEmpty() && added.isEmpty()) {
        continue;
      }
      if (removed.isEmpty()) {
        out.add(new Chunk(Kind.ADDED, List.of(), List.copyOf(added)));
      } else if (added.isEmpty()) {
        out.add(new Chunk(Kind.REMOVED, List.copyOf(removed), List.of()));
      } else {
        out.add(new Chunk(Kind.REPLACED, List.copyOf(removed), List.copyOf(added)));
      }
    }
    return out;
  }

  /** The differing lines, in order, for the categories asked for. */
  public static String render(String before, String after, EnumSet<Kind> wanted) {
    List<String> out = new ArrayList<>();
    for (Chunk chunk : chunks(before, after)) {
      if (!wanted.contains(chunk.kind())) {
        continue;
      }
      out.addAll(chunk.before());
      out.addAll(chunk.after());
    }
    return String.join("\n", out);
  }

  /** R21: trailing whitespace does not make a line differ. */
  private static List<String> lines(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    return Arrays.stream(text.split("\n", -1)).map(Diff::stripTrailing).toList();
  }

  private static String stripTrailing(String line) {
    int end = line.length();
    while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
      end--;
    }
    return line.substring(0, end);
  }

  /**
   * The differing regions of two line lists, as {aStart, aEnd, bStart, bEnd}.
   *
   * <p>Found by recursively taking the longest common run and diffing what lies either side of
   * it — the same decomposition Python's difflib performs, so the regions this reports and the
   * ones the source reports are the same regions.
   */
  private static List<int[]> opcodes(List<String> a, List<String> b) {
    List<int[]> out = new ArrayList<>();
    collect(a, b, 0, a.size(), 0, b.size(), out);
    return out;
  }

  private static void collect(
      List<String> a, List<String> b, int alo, int ahi, int blo, int bhi, List<int[]> out) {
    int[] match = longestMatch(a, b, alo, ahi, blo, bhi);
    int i = match[0];
    int j = match[1];
    int size = match[2];
    if (size == 0) {
      if (alo < ahi || blo < bhi) {
        out.add(new int[] {alo, ahi, blo, bhi});
      }
      return;
    }
    collect(a, b, alo, i, blo, j, out);
    collect(a, b, i + size, ahi, j + size, bhi, out);
  }

  private static int[] longestMatch(
      List<String> a, List<String> b, int alo, int ahi, int blo, int bhi) {
    Map<String, List<Integer>> positions = new HashMap<>();
    for (int j = blo; j < bhi; j++) {
      positions.computeIfAbsent(b.get(j), k -> new ArrayList<>()).add(j);
    }
    int bestI = alo;
    int bestJ = blo;
    int bestSize = 0;
    Map<Integer, Integer> runLengths = new HashMap<>();
    for (int i = alo; i < ahi; i++) {
      Map<Integer, Integer> next = new HashMap<>();
      for (int j : positions.getOrDefault(a.get(i), List.of())) {
        int length = runLengths.getOrDefault(j - 1, 0) + 1;
        next.put(j, length);
        if (length > bestSize) {
          bestI = i - length + 1;
          bestJ = j - length + 1;
          bestSize = length;
        }
      }
      runLengths = next;
    }
    return new int[] {bestI, bestJ, bestSize};
  }
}
