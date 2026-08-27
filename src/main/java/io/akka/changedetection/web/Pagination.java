package io.akka.changedetection.web;

import io.akka.changedetection.jinja.PyValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The page links under a long list.
 *
 * <p>Two windows decide which page numbers are offered: a run of pages either side of the one
 * being read, and the first and last few whatever page that is. The second window is what makes
 * "jump to the end" possible from anywhere, and the gap between them is shown as an ellipsis so
 * that the run of numbers cannot be mistaken for the whole list.
 */
public final class Pagination implements PyValue.Attributed {

  /** How many pages either side of the current one are offered. */
  private static final int INNER_WINDOW = 2;

  /** How many pages at each end are always offered. */
  private static final int OUTER_WINDOW = 1;

  private final int page;
  private final int total;
  private final int perPage;
  private final String path;
  private final Map<String, String> arguments;
  private final String recordName;
  private final String displayMessage;

  public Pagination(
      int page,
      int total,
      int perPage,
      String path,
      Map<String, String> arguments,
      String recordName,
      String displayMessage) {
    this.page = Math.max(1, page);
    this.total = total;
    this.perPage = Math.max(1, perPage);
    this.path = path;
    this.arguments = new LinkedHashMap<>(arguments);
    this.recordName = recordName;
    this.displayMessage = displayMessage;
  }

  public int totalPages() {
    return (int) Math.ceil(total / (double) perPage);
  }

  /** How many rows are skipped to reach this page. */
  public int skip() {
    return (page - 1) * perPage;
  }

  @Override
  public Object attribute(String name) {
    return switch (name) {
      case "page" -> page;
      case "total" -> total;
      case "per_page" -> perPage;
      case "skip" -> skip();
      case "pages" -> totalPages();
      case "info" -> new PyValue.Markup(info());
      case "links" -> new PyValue.Markup(links());
      case "has_prev" -> page > 1;
      case "has_next" -> page < totalPages();
      default -> PyValue.UNDEFINED;
    };
  }

  String info() {
    int start = total == 0 ? 0 : skip() + 1;
    int end = Math.min(total, skip() + perPage);
    String message =
        displayMessage
            .replace("{start}", String.valueOf(start))
            .replace("{end}", String.valueOf(end))
            .replace("{total}", String.valueOf(total))
            .replace("{record_name}", recordName);
    return "<div class=\"pagination-page-info\">" + message + "</div>";
  }

  String links() {
    int pages = totalPages();
    if (pages <= 1) {
      return "";
    }
    StringBuilder sb = new StringBuilder("<div class=\"ui pagination menu\">");
    sb.append(arrow("&laquo;", page > 1 ? page - 1 : 0));
    int previous = 0;
    for (int number : offered(pages)) {
      if (previous != 0 && number > previous + 1) {
        sb.append("<a class=\"disabled item\">...</a>");
      }
      if (number == page) {
        sb.append("<a class=\"item active\">").append(number).append("</a>");
      } else {
        sb.append("<a class=\"item\" href=\"")
            .append(href(number))
            .append("\">")
            .append(number)
            .append("</a>");
      }
      previous = number;
    }
    sb.append(arrow("&raquo;", page < pages ? page + 1 : 0));
    return sb.append("</div>").toString();
  }

  private String arrow(String glyph, int target) {
    if (target == 0) {
      return "<a class=\"item arrow disabled\">" + glyph + "</a>";
    }
    return "<a class=\"item arrow\" href=\"" + href(target) + "\">" + glyph + "</a>";
  }

  /**
   * The page numbers to offer, in order.
   *
   * <p>The run around the current page keeps its width at the ends of the list rather than
   * shrinking, so the number of links does not change as the reader walks to the last page.
   */
  List<Integer> offered(int pages) {
    int left = page - INNER_WINDOW;
    int right = page + INNER_WINDOW;
    if (left < 1) {
      right += 1 - left;
      left = 1;
    }
    if (right > pages) {
      left -= right - pages;
      right = pages;
    }
    left = Math.max(1, left);

    List<Integer> offered = new ArrayList<>();
    for (int number = 1; number <= pages; number++) {
      boolean inOuterLeft = number <= OUTER_WINDOW + 1;
      boolean inOuterRight = number > pages - OUTER_WINDOW - 1;
      boolean inInner = number >= left && number <= right;
      if (inOuterLeft || inOuterRight || inInner) {
        offered.add(number);
      }
    }
    return offered;
  }

  String href(int number) {
    Map<String, Object> query = new LinkedHashMap<>();
    if (number > 1) {
      query.put("page", number);
    }
    query.putAll(arguments);
    if (query.isEmpty()) {
      return path;
    }
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Object> entry : query.entrySet()) {
      parts.add(Routes.encode(entry.getKey()) + "=" + Routes.encode(String.valueOf(entry.getValue())));
    }
    return path + "?" + String.join("&", parts);
  }
}
