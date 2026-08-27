package io.akka.changedetection.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cuts a page down to the lines most likely to answer a question.
 *
 * <p>Sending a whole page costs tokens in proportion to its size and buries the part that
 * matters, so the lines are ranked against the intent and the highest-scoring ones kept, in the
 * order they appeared. Order is restored because a reordered page reads as nonsense and the
 * model would describe it as such.
 */
public final class Bm25Trim {

  public static final int MAX_CONTEXT_CHARS = 15_000;

  private static final double K1 = 1.5;
  private static final double B = 0.75;
  private static final double EPSILON = 0.25;

  private Bm25Trim() {}

  public static String trimToRelevant(String text, String query) {
    return trimToRelevant(text, query, MAX_CONTEXT_CHARS);
  }

  public static String trimToRelevant(String text, String query, int maxChars) {
    if (text == null || text.isEmpty() || query == null || query.isEmpty()) {
      return text == null ? "" : text;
    }
    if (text.length() <= maxChars) {
      return text;
    }
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      if (!line.strip().isEmpty()) {
        lines.add(line);
      }
    }
    if (lines.isEmpty()) {
      return text.substring(0, Math.min(maxChars, text.length()));
    }

    List<List<String>> tokenised = new ArrayList<>();
    for (String line : lines) {
      tokenised.add(words(line));
    }
    double[] scores = score(tokenised, words(query));

    List<Integer> order = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      order.add(index);
    }
    // Highest score first, and among equals the earlier line, which is what a stable sort of
    // the enumerated pairs gives.
    order.sort(Comparator.comparingDouble((Integer index) -> -scores[index]));

    List<Integer> selected = new ArrayList<>();
    int total = 0;
    for (int index : order) {
      int length = lines.get(index).length();
      if (total + length + 1 > maxChars) {
        break;
      }
      selected.add(index);
      total += length + 1;
    }
    selected.sort(Integer::compareTo);

    StringBuilder sb = new StringBuilder();
    for (int position = 0; position < selected.size(); position++) {
      if (position > 0) {
        sb.append('\n');
      }
      sb.append(lines.get(selected.get(position)));
    }
    return sb.toString();
  }

  private static List<String> words(String line) {
    List<String> out = new ArrayList<>();
    for (String word : line.toLowerCase(Locale.ROOT).split("\\s+")) {
      if (!word.isEmpty()) {
        out.add(word);
      }
    }
    return out;
  }

  /** Okapi BM25 with the same smoothing the original's library uses. */
  private static double[] score(List<List<String>> documents, List<String> query) {
    int count = documents.size();
    double totalLength = 0;
    List<Map<String, Integer>> frequencies = new ArrayList<>();
    Map<String, Integer> containing = new HashMap<>();
    for (List<String> document : documents) {
      totalLength += document.size();
      Map<String, Integer> frequency = new HashMap<>();
      for (String word : document) {
        frequency.merge(word, 1, Integer::sum);
      }
      frequencies.add(frequency);
      for (String word : frequency.keySet()) {
        containing.merge(word, 1, Integer::sum);
      }
    }
    double averageLength = count == 0 ? 0 : totalLength / count;

    Map<String, Double> weights = new HashMap<>();
    double negativeTotal = 0;
    int negativeCount = 0;
    double positiveTotal = 0;
    int positiveCount = 0;
    for (Map.Entry<String, Integer> entry : containing.entrySet()) {
      double weight = Math.log(count - entry.getValue() + 0.5) - Math.log(entry.getValue() + 0.5);
      weights.put(entry.getKey(), weight);
      if (weight < 0) {
        negativeTotal += weight;
        negativeCount++;
      } else {
        positiveTotal += weight;
        positiveCount++;
      }
    }
    // A word in almost every line scores negative, which would let a line be punished for
    // containing it; the floor keeps every contribution non-negative.
    double average = (positiveTotal + negativeTotal) / Math.max(1, positiveCount + negativeCount);
    double floor = EPSILON * average;
    for (Map.Entry<String, Double> entry : weights.entrySet()) {
      if (entry.getValue() < 0) {
        entry.setValue(floor);
      }
    }

    double[] scores = new double[count];
    for (int index = 0; index < count; index++) {
      Map<String, Integer> frequency = frequencies.get(index);
      double length = documents.get(index).size();
      double score = 0;
      for (String word : query) {
        Double weight = weights.get(word);
        if (weight == null) {
          continue;
        }
        int occurrences = frequency.getOrDefault(word, 0);
        score +=
            weight
                * occurrences
                * (K1 + 1)
                / (occurrences + K1 * (1 - B + B * length / (averageLength == 0 ? 1 : averageLength)));
      }
      scores[index] = score;
    }
    return scores;
  }
}
