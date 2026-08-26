package io.akka.changedetection.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The sequence comparison Python's {@code difflib.SequenceMatcher} performs, over lines.
 *
 * <p>Which opcodes come out is a behavioural fact about the original, not an implementation
 * detail: the diff renderer labels a run "replace" or "delete"+"insert" from them, and a
 * different matching algorithm partitions the same two inputs differently. So this is the
 * Ratcliff/Obershelp variant difflib uses -- longest matching block, recursed left and right --
 * including its junk and autojunk heuristics, rather than any library's diff.
 */
public final class SequenceMatcher {

  /** One matching block: a[i..i+size) equals b[j..j+size). */
  public record Match(int i, int j, int size) {}

  /** One edit operation over the two sequences. */
  public record OpCode(String tag, int i1, int i2, int j1, int j2) {}

  private final List<String> a;
  private final List<String> b;
  private final Predicate<String> isJunk;
  private final boolean autoJunk;

  private final Map<String, List<Integer>> b2j = new HashMap<>();
  private final Set<String> bJunk = new HashSet<>();
  private final Set<String> bPopular = new HashSet<>();

  private List<Match> matchingBlocks;
  private List<OpCode> opcodes;

  public SequenceMatcher(Predicate<String> isJunk, List<String> a, List<String> b) {
    this(isJunk, a, b, true);
  }

  public SequenceMatcher(
      Predicate<String> isJunk, List<String> a, List<String> b, boolean autoJunk) {
    this.isJunk = isJunk;
    this.a = a;
    this.b = b;
    this.autoJunk = autoJunk;
    chainB();
  }

  /**
   * The junk test the diff renderer passes. In Python it reads {@code lambda x: x in " \t"},
   * which is a substring test rather than a membership test, so it is true of the empty line, a
   * single space, a single tab, and the two-character line space-then-tab, and of nothing else.
   */
  public static Predicate<String> whitespaceLineIsJunk() {
    return line -> " \t".contains(line);
  }

  private void chainB() {
    for (int i = 0; i < b.size(); i++) {
      b2j.computeIfAbsent(b.get(i), k -> new ArrayList<>()).add(i);
    }
    if (isJunk != null) {
      for (String elt : b2j.keySet()) {
        if (isJunk.test(elt)) {
          bJunk.add(elt);
        }
      }
      for (String elt : bJunk) {
        b2j.remove(elt);
      }
    }
    int n = b.size();
    if (autoJunk && n >= 200) {
      int ntest = n / 100 + 1;
      for (Map.Entry<String, List<Integer>> e : b2j.entrySet()) {
        if (e.getValue().size() > ntest) {
          bPopular.add(e.getKey());
        }
      }
      for (String elt : bPopular) {
        b2j.remove(elt);
      }
    }
  }

  public Match findLongestMatch(int alo, int ahi, int blo, int bhi) {
    int besti = alo;
    int bestj = blo;
    int bestsize = 0;

    Map<Integer, Integer> j2len = new HashMap<>();
    for (int i = alo; i < ahi; i++) {
      Map<Integer, Integer> newj2len = new HashMap<>();
      List<Integer> indices = b2j.get(a.get(i));
      if (indices != null) {
        for (int j : indices) {
          if (j < blo) {
            continue;
          }
          if (j >= bhi) {
            break;
          }
          int k = j2len.getOrDefault(j - 1, 0) + 1;
          newj2len.put(j, k);
          if (k > bestsize) {
            besti = i - k + 1;
            bestj = j - k + 1;
            bestsize = k;
          }
        }
      }
      j2len = newj2len;
    }

    while (besti > alo
        && bestj > blo
        && !bJunk.contains(b.get(bestj - 1))
        && a.get(besti - 1).equals(b.get(bestj - 1))) {
      besti--;
      bestj--;
      bestsize++;
    }
    while (besti + bestsize < ahi
        && bestj + bestsize < bhi
        && !bJunk.contains(b.get(bestj + bestsize))
        && a.get(besti + bestsize).equals(b.get(bestj + bestsize))) {
      bestsize++;
    }

    while (besti > alo
        && bestj > blo
        && bJunk.contains(b.get(bestj - 1))
        && a.get(besti - 1).equals(b.get(bestj - 1))) {
      besti--;
      bestj--;
      bestsize++;
    }
    while (besti + bestsize < ahi
        && bestj + bestsize < bhi
        && bJunk.contains(b.get(bestj + bestsize))
        && a.get(besti + bestsize).equals(b.get(bestj + bestsize))) {
      bestsize++;
    }

    return new Match(besti, bestj, bestsize);
  }

  public List<Match> getMatchingBlocks() {
    if (matchingBlocks != null) {
      return matchingBlocks;
    }
    int la = a.size();
    int lb = b.size();
    List<int[]> queue = new ArrayList<>();
    queue.add(new int[] {0, la, 0, lb});
    List<Match> found = new ArrayList<>();
    while (!queue.isEmpty()) {
      int[] range = queue.remove(queue.size() - 1);
      Match m = findLongestMatch(range[0], range[1], range[2], range[3]);
      if (m.size() > 0) {
        found.add(m);
        if (range[0] < m.i() && range[2] < m.j()) {
          queue.add(new int[] {range[0], m.i(), range[2], m.j()});
        }
        if (m.i() + m.size() < range[1] && m.j() + m.size() < range[3]) {
          queue.add(new int[] {m.i() + m.size(), range[1], m.j() + m.size(), range[3]});
        }
      }
    }
    found.sort(
        (x, y) -> {
          int c = Integer.compare(x.i(), y.i());
          if (c != 0) {
            return c;
          }
          c = Integer.compare(x.j(), y.j());
          return c != 0 ? c : Integer.compare(x.size(), y.size());
        });

    List<Match> nonAdjacent = new ArrayList<>();
    int i1 = 0;
    int j1 = 0;
    int k1 = 0;
    for (Match m : found) {
      if (i1 + k1 == m.i() && j1 + k1 == m.j()) {
        k1 += m.size();
      } else {
        if (k1 > 0) {
          nonAdjacent.add(new Match(i1, j1, k1));
        }
        i1 = m.i();
        j1 = m.j();
        k1 = m.size();
      }
    }
    if (k1 > 0) {
      nonAdjacent.add(new Match(i1, j1, k1));
    }
    nonAdjacent.add(new Match(la, lb, 0));
    matchingBlocks = nonAdjacent;
    return matchingBlocks;
  }

  public List<OpCode> getOpCodes() {
    if (opcodes != null) {
      return opcodes;
    }
    int i = 0;
    int j = 0;
    List<OpCode> answer = new ArrayList<>();
    for (Match m : getMatchingBlocks()) {
      String tag = null;
      if (i < m.i() && j < m.j()) {
        tag = "replace";
      } else if (i < m.i()) {
        tag = "delete";
      } else if (j < m.j()) {
        tag = "insert";
      }
      if (tag != null) {
        answer.add(new OpCode(tag, i, m.i(), j, m.j()));
      }
      i = m.i() + m.size();
      j = m.j() + m.size();
      if (m.size() > 0) {
        answer.add(new OpCode("equal", m.i(), i, m.j(), j));
      }
    }
    opcodes = answer;
    return answer;
  }

  /** Groups of opcodes with n lines of context, as difflib's get_grouped_opcodes. */
  public List<List<OpCode>> getGroupedOpCodes(int n) {
    List<OpCode> codes = new ArrayList<>(getOpCodes());
    if (codes.isEmpty()) {
      codes.add(new OpCode("equal", 0, 1, 0, 1));
    }
    OpCode first = codes.get(0);
    if (first.tag().equals("equal")) {
      codes.set(
          0,
          new OpCode(
              "equal",
              Math.max(first.i1(), first.i2() - n),
              first.i2(),
              Math.max(first.j1(), first.j2() - n),
              first.j2()));
    }
    OpCode last = codes.get(codes.size() - 1);
    if (last.tag().equals("equal")) {
      codes.set(
          codes.size() - 1,
          new OpCode(
              "equal",
              last.i1(),
              Math.min(last.i2(), last.i1() + n),
              last.j1(),
              Math.min(last.j2(), last.j1() + n)));
    }

    int nn = n + n;
    List<List<OpCode>> groups = new ArrayList<>();
    List<OpCode> group = new ArrayList<>();
    for (OpCode c : codes) {
      int i1 = c.i1();
      int i2 = c.i2();
      int j1 = c.j1();
      int j2 = c.j2();
      if (c.tag().equals("equal") && i2 - i1 > nn) {
        group.add(new OpCode(c.tag(), i1, Math.min(i2, i1 + n), j1, Math.min(j2, j1 + n)));
        groups.add(group);
        group = new ArrayList<>();
        i1 = Math.max(i1, i2 - n);
        j1 = Math.max(j1, j2 - n);
      }
      group.add(new OpCode(c.tag(), i1, i2, j1, j2));
    }
    if (!group.isEmpty() && !(group.size() == 1 && group.get(0).tag().equals("equal"))) {
      groups.add(group);
    }
    return groups;
  }

  /** difflib.unified_diff, with difflib's own default of three lines of context. */
  public static List<String> unifiedDiff(List<String> a, List<String> b) {
    return unifiedDiff(a, b, "", "", "", "", 3, "\n");
  }

  public static List<String> unifiedDiff(
      List<String> a,
      List<String> b,
      String fromFile,
      String toFile,
      String fromFileDate,
      String toFileDate,
      int n,
      String lineterm) {
    List<String> out = new ArrayList<>();
    boolean started = false;
    SequenceMatcher matcher = new SequenceMatcher(null, a, b);
    for (List<OpCode> group : matcher.getGroupedOpCodes(n)) {
      if (!started) {
        started = true;
        String fdate = fromFileDate.isEmpty() ? "" : "\t" + fromFileDate;
        String tdate = toFileDate.isEmpty() ? "" : "\t" + toFileDate;
        out.add("--- " + fromFile + fdate + lineterm);
        out.add("+++ " + toFile + tdate + lineterm);
      }
      OpCode f = group.get(0);
      OpCode l = group.get(group.size() - 1);
      out.add(
          "@@ -"
              + formatRangeUnified(f.i1(), l.i2())
              + " +"
              + formatRangeUnified(f.j1(), l.j2())
              + " @@"
              + lineterm);
      for (OpCode c : group) {
        if (c.tag().equals("equal")) {
          for (String line : a.subList(c.i1(), c.i2())) {
            out.add(" " + line);
          }
          continue;
        }
        if (c.tag().equals("replace") || c.tag().equals("delete")) {
          for (String line : a.subList(c.i1(), c.i2())) {
            out.add("-" + line);
          }
        }
        if (c.tag().equals("replace") || c.tag().equals("insert")) {
          for (String line : b.subList(c.j1(), c.j2())) {
            out.add("+" + line);
          }
        }
      }
    }
    return out;
  }

  private static String formatRangeUnified(int start, int stop) {
    int beginning = start + 1;
    int length = stop - start;
    if (length == 1) {
      return String.valueOf(beginning);
    }
    if (length == 0) {
      beginning--;
    }
    return beginning + "," + length;
  }
}
