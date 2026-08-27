package io.akka.changedetection.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/**
 * How much two pictures of a page differ, as a percentage of their pixels.
 *
 * <p>Comparing pixels directly would report a change on every check: a page renders with tiny
 * differences in antialiasing and sub-pixel layout each time it is drawn. So the pictures are
 * reduced to grey, softened, and only pixels differing by more than a stated amount are
 * counted. Both the softening and the amount are what separate "the page changed" from "the
 * page was drawn again".
 */
public final class ImageComparison {

  /** How far a pixel must move to count, on the scale a grey value is measured on. */
  public static final double DEFAULT_PIXEL_THRESHOLD = 0.999;

  /** How much the picture is softened before comparing. */
  public static final double DEFAULT_BLUR_SIGMA = 3.0;

  private ImageComparison() {}

  /** A rectangle of the page to compare, rather than the whole of it. */
  public record Region(int left, int top, int right, int bottom) {}

  public static double changePercentage(
      byte[] fromBytes, byte[] toBytes, double pixelThreshold, double blurSigma, Region crop) {
    BufferedImage from = decode(fromBytes);
    BufferedImage to = decode(toBytes);
    if (from == null || to == null) {
      throw new IllegalArgumentException("one of the pictures could not be read");
    }
    if (crop != null) {
      from = cropped(from, crop);
      to = cropped(to, crop);
    }
    if (from.getWidth() != to.getWidth() || from.getHeight() != to.getHeight()) {
      from = resized(from, to.getWidth(), to.getHeight());
    }

    double[][] greyFrom = greyscale(from);
    double[][] greyTo = greyscale(to);

    if (blurSigma > 0) {
      int kernelSize = (int) (2 * Math.round(3 * blurSigma)) + 1;
      if (kernelSize % 2 == 0) {
        kernelSize++;
      }
      double[] kernel = gaussianKernel(kernelSize, blurSigma);
      greyFrom = blur(greyFrom, kernel);
      greyTo = blur(greyTo, kernel);
    }

    int height = greyTo.length;
    int width = height == 0 ? 0 : greyTo[0].length;
    long total = (long) width * height;
    if (total == 0) {
      return 0;
    }
    long changed = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        double difference = Math.abs(Math.round(greyFrom[y][x]) - Math.round(greyTo[y][x]));
        if (difference > (int) pixelThreshold) {
          changed++;
        }
      }
    }
    return (changed / (double) total) * 100.0;
  }

  /** The largest a rendered difference is drawn at, so a very tall page stays affordable. */
  public static final int MAX_DIFF_WIDTH = 2000;

  public static final int MAX_DIFF_HEIGHT = 8000;

  /**
   * The newer picture with everything that changed washed red.
   *
   * <p>Drawn over the newer picture rather than side by side, because what a person wants from
   * this is "where on the page", and two pictures side by side make that a search.
   *
   * @return a JPEG, or null when either picture could not be read
   */
  public static byte[] renderedDifference(
      byte[] fromBytes, byte[] toBytes, double pixelThreshold, double blurSigma) {
    BufferedImage from = decode(fromBytes);
    BufferedImage to = decode(toBytes);
    if (from == null || to == null) {
      return null;
    }
    if (from.getWidth() != to.getWidth() || from.getHeight() != to.getHeight()) {
      from = resized(from, to.getWidth(), to.getHeight());
    }
    int width = to.getWidth();
    int height = to.getHeight();
    if (width > MAX_DIFF_WIDTH || height > MAX_DIFF_HEIGHT) {
      double scale = Math.min(MAX_DIFF_WIDTH / (double) width, MAX_DIFF_HEIGHT / (double) height);
      int newWidth = (int) (width * scale);
      int newHeight = (int) (height * scale);
      from = resized(from, newWidth, newHeight);
      to = resized(to, newWidth, newHeight);
      width = newWidth;
      height = newHeight;
    }

    double[][] greyFrom = greyscale(from);
    double[][] greyTo = greyscale(to);
    if (blurSigma > 0) {
      int kernelSize = (int) (2 * Math.round(3 * blurSigma)) + 1;
      if (kernelSize % 2 == 0) {
        kernelSize++;
      }
      double[] kernel = gaussianKernel(kernelSize, blurSigma);
      greyFrom = blur(greyFrom, kernel);
      greyTo = blur(greyTo, kernel);
    }

    BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int rgb = to.getRGB(x, y);
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        double difference = Math.abs(greyFrom[y][x] - greyTo[y][x]);
        if (difference > (int) pixelThreshold) {
          red = clamp((int) (red * 0.5 + 127), 0, 255);
          green = (int) (green * 0.5);
          blue = (int) (blue * 0.5);
        }
        overlay.setRGB(x, y, (red << 16) | (green << 8) | blue);
      }
    }

    try {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(overlay, "jpeg", out);
      return out.toByteArray();
    } catch (java.io.IOException e) {
      return null;
    }
  }

  private static BufferedImage decode(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    try {
      return ImageIO.read(new ByteArrayInputStream(bytes));
    } catch (Exception e) {
      return null;
    }
  }

  private static BufferedImage cropped(BufferedImage image, Region region) {
    int left = Math.max(0, Math.min(region.left(), image.getWidth()));
    int top = Math.max(0, Math.min(region.top(), image.getHeight()));
    int right = Math.max(left, Math.min(region.right(), image.getWidth()));
    int bottom = Math.max(top, Math.min(region.bottom(), image.getHeight()));
    if (right == left || bottom == top) {
      return image;
    }
    return image.getSubimage(left, top, right - left, bottom - top);
  }

  private static BufferedImage resized(BufferedImage image, int width, int height) {
    BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = out.createGraphics();
    graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    graphics.drawImage(image, 0, 0, width, height, null);
    graphics.dispose();
    return out;
  }

  /** Grey as the picture library computes it, which is not the same as the average. */
  private static double[][] greyscale(BufferedImage image) {
    int width = image.getWidth();
    int height = image.getHeight();
    double[][] grey = new double[height][width];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        grey[y][x] = 0.299 * red + 0.587 * green + 0.114 * blue;
      }
    }
    return grey;
  }

  private static double[] gaussianKernel(int size, double sigma) {
    double[] kernel = new double[size];
    int centre = size / 2;
    double sum = 0;
    for (int i = 0; i < size; i++) {
      double x = i - centre;
      kernel[i] = Math.exp(-(x * x) / (2 * sigma * sigma));
      sum += kernel[i];
    }
    for (int i = 0; i < size; i++) {
      kernel[i] /= sum;
    }
    return kernel;
  }

  /** A separable blur: the same kernel applied across and then down. */
  private static double[][] blur(double[][] image, double[] kernel) {
    int height = image.length;
    int width = height == 0 ? 0 : image[0].length;
    int radius = kernel.length / 2;
    double[][] horizontal = new double[height][width];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        double total = 0;
        for (int k = 0; k < kernel.length; k++) {
          int sample = clamp(x + k - radius, 0, width - 1);
          total += image[y][sample] * kernel[k];
        }
        horizontal[y][x] = total;
      }
    }
    double[][] out = new double[height][width];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        double total = 0;
        for (int k = 0; k < kernel.length; k++) {
          int sample = clamp(y + k - radius, 0, height - 1);
          total += horizontal[sample][x] * kernel[k];
        }
        out[y][x] = total;
      }
    }
    return out;
  }

  private static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(high, value));
  }
}
