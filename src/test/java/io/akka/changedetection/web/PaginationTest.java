package io.akka.changedetection.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The page links are the ones the original offers, character for character. */
class PaginationTest {

  private Pagination of(int page, int total, Map<String, String> arguments) {
    return new Pagination(
        page, total, 50, "/", arguments, "records",
        "displaying <b>{start} - {end}</b> {record_name} in total <b>{total}</b>");
  }

  @Test
  void aSinglePageOffersNoLinks() {
    assertEquals("", of(1, 12, Map.of()).links());
  }

  @Test
  void theFirstPageOfTwelve() {
    assertEquals(
        "<div class=\"ui pagination menu\">"
            + "<a class=\"item arrow disabled\">&laquo;</a>"
            + "<a class=\"item active\">1</a>"
            + "<a class=\"item\" href=\"/?page=2\">2</a>"
            + "<a class=\"item\" href=\"/?page=3\">3</a>"
            + "<a class=\"item\" href=\"/?page=4\">4</a>"
            + "<a class=\"item\" href=\"/?page=5\">5</a>"
            + "<a class=\"disabled item\">...</a>"
            + "<a class=\"item\" href=\"/?page=11\">11</a>"
            + "<a class=\"item\" href=\"/?page=12\">12</a>"
            + "<a class=\"item arrow\" href=\"/?page=2\">&raquo;</a>"
            + "</div>",
        of(1, 559, Map.of()).links());
  }

  @Test
  void aMiddlePageHasAGapOnBothSides() {
    assertEquals(
        "<div class=\"ui pagination menu\">"
            + "<a class=\"item arrow\" href=\"/?page=5\">&laquo;</a>"
            + "<a class=\"item\" href=\"/\">1</a>"
            + "<a class=\"item\" href=\"/?page=2\">2</a>"
            + "<a class=\"disabled item\">...</a>"
            + "<a class=\"item\" href=\"/?page=4\">4</a>"
            + "<a class=\"item\" href=\"/?page=5\">5</a>"
            + "<a class=\"item active\">6</a>"
            + "<a class=\"item\" href=\"/?page=7\">7</a>"
            + "<a class=\"item\" href=\"/?page=8\">8</a>"
            + "<a class=\"disabled item\">...</a>"
            + "<a class=\"item\" href=\"/?page=11\">11</a>"
            + "<a class=\"item\" href=\"/?page=12\">12</a>"
            + "<a class=\"item arrow\" href=\"/?page=7\">&raquo;</a>"
            + "</div>",
        of(6, 559, Map.of()).links());
  }

  @Test
  void theLastPageKeepsTheRunTheSameWidth() {
    assertEquals(
        "<div class=\"ui pagination menu\">"
            + "<a class=\"item arrow\" href=\"/?page=11\">&laquo;</a>"
            + "<a class=\"item\" href=\"/\">1</a>"
            + "<a class=\"item\" href=\"/?page=2\">2</a>"
            + "<a class=\"disabled item\">...</a>"
            + "<a class=\"item\" href=\"/?page=8\">8</a>"
            + "<a class=\"item\" href=\"/?page=9\">9</a>"
            + "<a class=\"item\" href=\"/?page=10\">10</a>"
            + "<a class=\"item\" href=\"/?page=11\">11</a>"
            + "<a class=\"item active\">12</a>"
            + "<a class=\"item arrow disabled\">&raquo;</a>"
            + "</div>",
        of(12, 559, Map.of()).links());
  }

  @Test
  void theCurrentFilteringSurvivesEveryLink() {
    Map<String, String> arguments = new LinkedHashMap<>();
    arguments.put("q", "probe");
    arguments.put("order", "asc");
    assertEquals(
        "<div class=\"ui pagination menu\">"
            + "<a class=\"item arrow\" href=\"/?page=2&q=probe&order=asc\">&laquo;</a>"
            + "<a class=\"item\" href=\"/?q=probe&order=asc\">1</a>"
            + "<a class=\"item\" href=\"/?page=2&q=probe&order=asc\">2</a>"
            + "<a class=\"item active\">3</a>"
            + "<a class=\"item\" href=\"/?page=4&q=probe&order=asc\">4</a>"
            + "<a class=\"item\" href=\"/?page=5&q=probe&order=asc\">5</a>"
            + "<a class=\"disabled item\">...</a>"
            + "<a class=\"item\" href=\"/?page=11&q=probe&order=asc\">11</a>"
            + "<a class=\"item\" href=\"/?page=12&q=probe&order=asc\">12</a>"
            + "<a class=\"item arrow\" href=\"/?page=4&q=probe&order=asc\">&raquo;</a>"
            + "</div>",
        of(3, 559, arguments).links());
  }

  @Test
  void theCountReadsAsTheOriginalWritesIt() {
    assertEquals(
        "<div class=\"pagination-page-info\">displaying <b>1 - 50</b> records in total"
            + " <b>559</b></div>",
        of(1, 559, Map.of()).info());
    assertEquals(
        "<div class=\"pagination-page-info\">displaying <b>551 - 559</b> records in total"
            + " <b>559</b></div>",
        of(12, 559, Map.of()).info());
  }
}
