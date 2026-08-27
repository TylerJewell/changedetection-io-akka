package io.akka.changedetection.processors;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import io.akka.changedetection.text.PythonJson;
import io.akka.changedetection.text.PythonText;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decision for a watch that is following how a page looks rather than what it says.
 *
 * <p>It exists because some changes are invisible to text: an image swapped, a chart redrawn, a
 * layout shifted. What it compares is two pictures of the page, so it needs a fetcher that can
 * take one -- and it says so plainly rather than reporting no change, because a watch that
 * quietly never fires is worse than one that says why.
 *
 * <p>The area compared can be narrowed two ways: a rectangle the operator drew, or the element
 * a selector picks out, whose position on the page the browser reported when it fetched.
 */
public final class ImageSsimProcessor {

  /** The processor's own settings, kept apart from the watch's own fields. */
  public interface Environment extends RestockProcessor.Environment {}

  private final Environment environment;

  public ImageSsimProcessor(Environment environment) {
    this.environment = environment;
  }

  public CheckOutcome run(Watch watch, Fetched fetched) {
    if (fetched.screenshot == null || fetched.screenshot.length == 0) {
      throw new ProcessorExceptions.ProcessorException(
          "No screenshot available. Ensure the watch is configured to use a real browser.",
          fetched.statusCode);
    }

    String currentChecksum = PythonText.md5Hex(fetched.screenshot);
    Object previousChecksum = watch.fields().get("previous_md5");
    if (previousChecksum instanceof String previous
        && !previous.isEmpty()
        && previous.equals(currentChecksum)) {
      throw new ProcessorExceptions.ChecksumWasTheSame();
    }

    Map<String, Object> config =
        environment.processorConfig(watch.uuid(), "image_ssim_diff.json");
    Fields settings = new Fields(config);
    Map<String, Object> application = environment.application();

    double pixelThreshold =
        firstNumber(
            settings.number("pixel_difference_threshold_sensitivity"),
            new Fields(application).number("pixel_difference_threshold_sensitivity"),
            ImageComparison.DEFAULT_PIXEL_THRESHOLD);
    double minimumChangePercent =
        firstNumber(
            settings.number("min_change_percentage"),
            new Fields(application).number("min_change_percentage"),
            1.0);

    ImageComparison.Region crop = boundingBox(settings.string("bounding_box"));
    if (crop == null) {
      crop = regionOfFirstFilter(watch, fetched.xpathData);
    }

    Map<String, Object> updates = new LinkedHashMap<>();
    updates.put("previous_md5", currentChecksum);
    updates.put("last_error", false);

    String encoded = Base64.getEncoder().encodeToString(fetched.screenshot);

    if (watch.history().isEmpty()) {
      // The first picture is the baseline. There is nothing to compare it against, and calling
      // that a change would notify on every watch the moment it is created.
      return CheckOutcome.of(false, updates, encoded);
    }

    long previousTimestamp = watch.history().get(watch.history().size() - 1);
    String previousEncoded = environment.snapshotFor(watch.uuid(), previousTimestamp);
    if (previousEncoded == null || previousEncoded.isEmpty()) {
      return CheckOutcome.of(false, updates, encoded);
    }

    byte[] previousBytes;
    try {
      previousBytes = Base64.getDecoder().decode(previousEncoded);
    } catch (IllegalArgumentException e) {
      previousBytes = previousEncoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    double changeScore;
    try {
      changeScore =
          ImageComparison.changePercentage(
              previousBytes,
              fetched.screenshot,
              pixelThreshold,
              ImageComparison.DEFAULT_BLUR_SIGMA,
              crop);
    } catch (RuntimeException e) {
      throw new ProcessorExceptions.ProcessorException(
          "Screenshot comparison failed: " + e.getMessage(), fetched.statusCode);
    }

    boolean changed = changeScore > minimumChangePercent;
    return CheckOutcome.of(changed, updates, encoded);
  }

  private static double firstNumber(Double first, Double second, double fallback) {
    if (first != null && first != 0) {
      return first;
    }
    if (second != null && second != 0) {
      return second;
    }
    return fallback;
  }

  /** A rectangle the operator drew, written as four numbers. */
  static ImageComparison.Region boundingBox(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String[] parts = value.split(",");
    if (parts.length != 4) {
      return null;
    }
    try {
      int x = Integer.parseInt(parts[0].strip());
      int y = Integer.parseInt(parts[1].strip());
      int width = Integer.parseInt(parts[2].strip());
      int height = Integer.parseInt(parts[3].strip());
      return new ImageComparison.Region(
          Math.max(0, x), Math.max(0, y), x + width, y + height);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Where the element a selector picks out sat on the page.
   *
   * <p>Read from what the browser measured during the fetch, because the position is a fact
   * about the rendered page and cannot be worked out from the markup.
   */
  static ImageComparison.Region regionOfFirstFilter(Watch watch, String xpathData) {
    List<String> filters = watch.fields().strings("include_filters");
    if (filters.isEmpty() || xpathData == null || xpathData.isBlank()) {
      return null;
    }
    String first = filters.get(0).strip();
    if (first.isEmpty()) {
      return null;
    }
    try {
      JsonNode parsed = PythonJson.MAPPER.readTree(xpathData);
      for (JsonNode element : parsed.path("size_pos")) {
        if (element.path("xpath").asText("").equals(first)
            && element.path("highlight_as_custom_filter").asBoolean(false)) {
          int left = element.path("left").asInt(0);
          int top = element.path("top").asInt(0);
          int width = element.path("width").asInt(0);
          int height = element.path("height").asInt(0);
          return new ImageComparison.Region(
              Math.max(0, left), Math.max(0, top), left + width, top + height);
        }
      }
    } catch (Exception e) {
      return null;
    }
    return null;
  }
}
