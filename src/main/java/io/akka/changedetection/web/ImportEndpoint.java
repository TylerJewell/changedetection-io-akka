package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.forms.Form;
import io.akka.changedetection.forms.Forms;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.model.UrlSafety;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bringing in watches somebody else's tool wrote down.
 *
 * <p>Four shapes are accepted and they are not variations of one reader: a plain list of
 * addresses with tags after each, an export from Distill, and two spreadsheet layouts -- one
 * fixed to what Wachete exports and one where the operator says which column means what. Each
 * reports what it took and what it left behind rather than only what it took, because a silent
 * import that dropped half the file looks exactly like one that did not.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ImportEndpoint extends AbstractHttpEndpoint {

  /** How many a single submission may bring in before the rest is left for another go. */
  private static final int BATCH_LIMIT = 5000;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ComponentClient componentClient;

  public ImportEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/imports/import")
  public HttpResponse importPage() {
    Store store = new io.akka.changedetection.application.Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/imports/import", "imports");
    HttpResponse refusal = Guard.requireSignIn(page, "/imports/import");
    if (refusal != null) {
      return refusal;
    }
    return page.session().attachTo(Requests.html(render(page, Forms.importWatches(), "")));
  }

  @Post("/imports/import")
  public HttpResponse importSubmit(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/imports/import", "imports");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    List<String> remaining = new ArrayList<>();

    String urls = submitted.first("urls");
    if (!urls.strip().isEmpty()) {
      String processor = submitted.first("processor");
      remaining = importUrlList(operations, page, urls,
          processor.isEmpty() ? io.akka.changedetection.forms.Choices.defaultProcessor()
              : processor);
      if (remaining.isEmpty()) {
        return page.session().attachTo(Requests.redirect("/"));
      }
    }

    String distill = submitted.first("distill-io");
    if (!distill.strip().isEmpty()) {
      importDistill(operations, page, distill);
    }

    Requests.Upload workbook = submitted.upload("xlsx_file");
    if (workbook != null && workbook.content().length > 0) {
      if ("wachete".equals(submitted.first("file_mapping"))) {
        importWachete(operations, page, workbook.content());
      } else {
        Map<Integer, String> profile = new LinkedHashMap<>();
        for (int index = 0; index < 10; index++) {
          String column = submitted.first("custom_xlsx[col_" + index + "]");
          String meaning = submitted.first("custom_xlsx[col_type_" + index + "]");
          if (!column.isEmpty() && !meaning.isEmpty()) {
            try {
              profile.put(Integer.parseInt(column.strip()), meaning);
            } catch (NumberFormatException e) {
              // A column that is not a number names nothing, so it maps nothing.
            }
          }
        }
        importCustomWorkbook(operations, page, workbook.content(), profile);
      }
    }

    Form form = Forms.importWatches();
    form.populate(submitted.values());
    return page.session()
        .attachTo(Requests.html(render(page, form, String.join("\n", remaining))));
  }

  // ----------------------------------------------------------------- readers

  /** A list of addresses, each optionally followed by the tags it belongs to. */
  private List<String> importUrlList(
      Operations operations, Render.Page page, String data, String processor) {
    long began = System.nanoTime();
    List<String> remaining = new ArrayList<>();
    String[] lines = data.split("\n");
    if (lines.length > BATCH_LIMIT) {
      page.session()
          .flash(
              "Importing 5,000 of the first URLs from your list, the rest can be imported"
                  + " again.");
    }
    int good = 0;
    for (String line : lines) {
      String url = line.strip();
      if (url.isEmpty()) {
        continue;
      }
      String tags = "";
      int space = url.indexOf(' ');
      if (space > 0) {
        tags = url.substring(space + 1);
        url = url.substring(0, space);
      }
      if (!url.isEmpty()
          && url.toLowerCase(Locale.ROOT).contains("http")
          && good < BATCH_LIMIT) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("processor", processor);
        String created = operations.addWatch(url.strip(), tags, extras);
        if (created != null) {
          good++;
          continue;
        }
      }
      remaining.add(url);
    }
    page.session()
        .flash(
            good + " Imported from list in " + seconds(began) + "s, " + remaining.size()
                + " Skipped.");
    return remaining;
  }

  private void importDistill(Operations operations, Render.Page page, String data) {
    long began = System.nanoTime();
    JsonNode parsed;
    try {
      parsed = MAPPER.readTree(data.strip());
    } catch (Exception e) {
      page.session().flash("Unable to read JSON file, was it broken?", "error");
      return;
    }
    JsonNode entries = parsed.get("data");
    if (entries == null || !entries.isArray()) {
      page.session().flash("JSON structure looks invalid, was it broken?", "error");
      return;
    }
    int good = 0;
    for (JsonNode entry : entries) {
      String uri = entry.path("uri").asText("");
      if (uri.isEmpty() || good >= BATCH_LIMIT) {
        continue;
      }
      Map<String, Object> extras = new LinkedHashMap<>();
      if (entry.hasNonNull("name")) {
        extras.put("title", entry.get("name").asText());
      }
      JsonNode config;
      try {
        config = MAPPER.readTree(entry.path("config").asText("{}"));
      } catch (Exception e) {
        config = MAPPER.createObjectNode();
      }
      JsonNode frame = config.path("selections").path(0).path("frames").path(0);
      JsonNode exclude = frame.path("excludes").path(0);
      // Only the selector kind is understood; anything else is left off rather than guessed at.
      if ("css".equals(exclude.path("type").asText(""))) {
        extras.put("subtractive_selectors", exclude.path("expr").asText(""));
      }
      List<String> includeFilters = new ArrayList<>();
      JsonNode include = frame.path("includes").path(0);
      if (include.has("expr")) {
        includeFilters.add(
            "xpath".equals(include.path("type").asText(""))
                ? "xpath:" + include.path("expr").asText("")
                : include.path("expr").asText(""));
      }
      extras.put("include_filters", includeFilters);

      List<String> tags = new ArrayList<>();
      for (JsonNode tag : entry.path("tags")) {
        tags.add(tag.asText());
      }
      if (operations.addWatch(uri.strip(), String.join(",", tags), extras) != null) {
        good++;
      }
    }
    page.session()
        .flash(good + " Imported from Distill.io in " + seconds(began) + "s, 0 Skipped.");
  }

  /** The layout Wachete exports, where the first row names the columns. */
  private void importWachete(Operations operations, Render.Page page, byte[] workbook) {
    long began = System.nanoTime();
    List<XlsxReader.Row> rows;
    try {
      rows = XlsxReader.rows(workbook);
    } catch (RuntimeException e) {
      page.session()
          .flash("Unable to read export XLSX file, something wrong with the file?", "error");
      return;
    }
    if (rows.isEmpty()) {
      page.session()
          .flash("Unable to read export XLSX file, something wrong with the file?", "error");
      return;
    }
    Map<Integer, String> headings = new LinkedHashMap<>();
    for (Map.Entry<Integer, String> cell : rows.get(0).cells().entrySet()) {
      headings.put(cell.getKey(), cell.getValue().strip().toLowerCase(Locale.ROOT));
    }

    int good = 0;
    for (int index = 1; index < rows.size(); index++) {
      XlsxReader.Row row = rows.get(index);
      Map<String, String> named = new LinkedHashMap<>();
      for (Map.Entry<Integer, String> cell : row.cells().entrySet()) {
        String heading = headings.get(cell.getKey());
        if (heading != null) {
          named.put(heading, cell.getValue());
        }
      }
      Map<String, Object> extras = new LinkedHashMap<>();
      // Spreadsheet tools write a boolean several ways, so the value is read for what it says
      // rather than compared against one spelling.
      String dynamic = named.getOrDefault("dynamic wachet", "").strip().toLowerCase(Locale.ROOT);
      if (dynamic.contains("true") || dynamic.equals("1")) {
        extras.put("fetch_backend", "html_webdriver");
      } else if (dynamic.contains("false") || dynamic.equals("0")) {
        extras.put("fetch_backend", "html_requests");
      }
      if (named.containsKey("xpath")) {
        extras.put("include_filters", List.of(named.get("xpath")));
      }
      if (named.containsKey("name")) {
        extras.put("title", named.get("name").strip());
      }
      if (named.containsKey("interval (min)")) {
        try {
          extras.put(
              "time_between_check", intervalOf(Integer.parseInt(
                  named.get("interval (min)").strip().split("\\.")[0])));
        } catch (NumberFormatException e) {
          page.session()
              .flash(
                  "Error processing row number " + row.number()
                      + ", check all cell data types are correct, row was skipped.",
                  "error");
          continue;
        }
      }
      String url = named.getOrDefault("url", "").strip();
      if (url.isEmpty()) {
        continue;
      }
      if (!UrlSafety.isSafeValidUrl(url, false)) {
        page.session()
            .flash(
                "Error processing row number " + row.number()
                    + ", URL value was incorrect, row was skipped.",
                "error");
        continue;
      }
      if (operations.addWatch(url, named.get("folder"), extras) != null) {
        good++;
      }
    }
    page.session().flash(good + " imported from Wachete .xlsx in " + seconds(began) + "s");
  }

  /** Any layout, where the operator said which column carries what. */
  private void importCustomWorkbook(
      Operations operations, Render.Page page, byte[] workbook, Map<Integer, String> profile) {
    long began = System.nanoTime();
    List<XlsxReader.Row> rows;
    try {
      rows = XlsxReader.rows(workbook);
    } catch (RuntimeException e) {
      page.session()
          .flash("Unable to read export XLSX file, something wrong with the file?", "error");
      return;
    }
    int good = 0;
    for (XlsxReader.Row row : rows) {
      String url = null;
      String tags = null;
      Map<String, Object> extras = new LinkedHashMap<>();
      boolean skip = false;

      for (Map.Entry<Integer, String> cell : row.cells().entrySet()) {
        String meaning = profile.get(cell.getKey());
        if (meaning == null) {
          continue;
        }
        String value = cell.getValue().strip();
        switch (meaning) {
          case "url" -> {
            if (!UrlSafety.isSafeValidUrl(value, false)) {
              page.session()
                  .flash(
                      "Error processing row number " + row.number()
                          + ", URL value was incorrect, row was skipped.",
                      "error");
              skip = true;
            } else {
              url = value;
            }
          }
          case "tag" -> tags = value;
          case "include_filters" -> extras.put("include_filters", List.of(value));
          case "interval_minutes" -> {
            try {
              extras.put("time_between_check",
                  intervalOf(Integer.parseInt(value.split("\\.")[0])));
            } catch (NumberFormatException e) {
              page.session()
                  .flash(
                      "Error processing row number " + row.number()
                          + ", check all cell data types are correct, row was skipped.",
                      "error");
              skip = true;
            }
          }
          default -> extras.put(meaning, value);
        }
        if (skip) {
          break;
        }
      }
      if (skip || url == null) {
        continue;
      }
      if (operations.addWatch(url, tags, extras) != null) {
        good++;
      }
    }
    page.session().flash(good + " imported from custom .xlsx in " + seconds(began) + "s");
  }

  /** Minutes, carried up into the units a watch's interval is stored in. */
  static Map<String, Object> intervalOf(int totalMinutes) {
    int hours = totalMinutes / 60;
    int minutes = totalMinutes % 60;
    int days = hours / 24;
    hours = hours % 24;
    int weeks = days / 7;
    days = days % 7;
    Map<String, Object> interval = new LinkedHashMap<>();
    interval.put("weeks", weeks);
    interval.put("days", days);
    interval.put("hours", hours);
    interval.put("minutes", minutes);
    interval.put("seconds", 0);
    return interval;
  }

  private String render(Render.Page page, Form form, String remaining) {
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("form", form);
    variables.put("import_url_list_remaining", remaining);
    variables.put("original_distill_json", "");
    return Render.render(page, "import.html", variables);
  }

  private static String seconds(long began) {
    return String.format("%.2f", (System.nanoTime() - began) / 1_000_000_000.0);
  }
}
