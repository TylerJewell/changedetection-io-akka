package io.akka.changedetection.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.processors.ProcessorExceptions;
import io.akka.changedetection.text.PythonJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The operations an operator can script a browser through before the page is read.
 *
 * <p>These are how a page behind a form, a cookie banner or a "load more" button is reached at
 * all, so the list of them is a list of capabilities and not an implementation detail: every
 * one that is missing is a page the rebuild cannot watch.
 *
 * <p>Each step names an operation, sometimes a selector and sometimes a value; which of the two
 * a step takes is declared here because the interface reads that declaration to decide which
 * field to enable.
 */
public final class BrowserSteps {

  /** For each operation, whether it takes a selector and whether it takes a value. */
  public static final Map<String, String> UI_CONFIG = new LinkedHashMap<>();

  static {
    UI_CONFIG.put("Choose one", "0 0");
    UI_CONFIG.put("Check checkbox", "1 0");
    UI_CONFIG.put("Click X,Y", "0 1");
    UI_CONFIG.put("Click element if exists", "1 0");
    UI_CONFIG.put("Click element", "1 0");
    UI_CONFIG.put("Click element containing text", "0 1");
    UI_CONFIG.put("Click element containing text if exists", "0 1");
    UI_CONFIG.put("Enter text in field", "1 1");
    UI_CONFIG.put("Execute JS", "0 1");
    UI_CONFIG.put("Goto site", "0 0");
    UI_CONFIG.put("Goto URL", "0 1");
    UI_CONFIG.put("Make all child elements visible", "1 0");
    UI_CONFIG.put("Press Enter", "0 0");
    UI_CONFIG.put("Select by label", "1 1");
    UI_CONFIG.put("<select> by option text", "1 1");
    UI_CONFIG.put("Scroll down", "0 0");
    UI_CONFIG.put("Uncheck checkbox", "1 0");
    UI_CONFIG.put("Wait for seconds", "0 1");
    UI_CONFIG.put("Wait for text", "0 1");
    UI_CONFIG.put("Wait for text in element", "1 1");
    UI_CONFIG.put("Remove elements", "1 0");
  }

  private static final Pattern COORDINATES = Pattern.compile("^\\s?\\d+\\s?,\\s?\\d+\\s?$");
  private static final int ACTION_TIMEOUT_MILLIS = 10 * 1000;

  private BrowserSteps() {}

  /**
   * The steps worth running.
   *
   * <p>A step left on the placeholder operation is dropped, and a leading "go to the site" step
   * is dropped too -- the browser is already there, and running it would navigate a second time.
   */
  public static List<Map<String, Object>> validSteps(List<Map<String, Object>> steps) {
    List<Map<String, Object>> valid = new ArrayList<>();
    if (steps == null) {
      return valid;
    }
    for (Map<String, Object> step : steps) {
      Object operation = step.get("operation");
      if (operation == null) {
        continue;
      }
      String name = String.valueOf(operation);
      if (name.isEmpty() || name.equals("Choose one")) {
        continue;
      }
      valid.add(step);
    }
    if (!valid.isEmpty() && "Goto site".equals(String.valueOf(valid.get(0).get("operation")))) {
      valid.remove(0);
    }
    return valid;
  }

  /** Runs one step against an attached page. */
  public static void run(
      CdpClient client,
      String sessionId,
      Map<String, Object> step,
      String startUrl,
      int stepNumber) {
    String operation = String.valueOf(step.get("operation"));
    String selector = step.get("selector") == null ? "" : String.valueOf(step.get("selector"));
    String value =
        step.get("optional_value") == null ? "" : String.valueOf(step.get("optional_value"));

    try {
      switch (operation) {
        case "Goto URL" -> gotoUrl(client, sessionId, value);
        case "Goto site" -> gotoUrl(client, sessionId,
            startUrl.replaceFirst("(?i)^source:", ""));
        case "Click element" -> evaluate(client, sessionId,
            "(function(){var e=document.querySelector(" + json(selector)
                + ");if(e){e.click();}})()");
        case "Click element if exists" -> evaluate(client, sessionId,
            "(function(){var e=document.querySelector(" + json(selector)
                + ");if(e){e.click();}})()");
        case "Click element containing text",
             "Click element containing text if exists" -> evaluate(client, sessionId,
            "(function(){var v=" + json(value) + ";"
                + "var all=document.querySelectorAll('*');"
                + "for(var i=0;i<all.length;i++){"
                + "if(all[i].innerText && all[i].innerText.includes(v)"
                + "&& all[i].children.length===0){all[i].click();return;}}})()");
        case "Enter text in field" -> evaluate(client, sessionId,
            "(function(){var e=document.querySelector(" + json(selector) + ");"
                + "if(e){e.focus();e.value=" + json(value) + ";"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));}})()");
        case "Execute JS" -> evaluate(client, sessionId, value);
        case "Click X,Y" -> clickAt(client, sessionId, value);
        case "Select by label", "<select> by option text" -> evaluate(client, sessionId,
            "(function(){var e=document.querySelector(" + json(selector) + ");if(!e)return;"
                + "for(var i=0;i<e.options.length;i++){"
                + "if(e.options[i].text===" + json(value) + "){e.selectedIndex=i;"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));return;}}})()");
        case "Scroll down" -> {
          evaluate(client, sessionId, "window.scrollBy(0, 600)");
          sleep(1000);
        }
        case "Wait for seconds" -> sleep((long) (parseSeconds(value) * 1000));
        case "Wait for text" -> waitFor(client, sessionId,
            "document.querySelector('body').innerText.includes(" + json(value) + ")");
        case "Wait for text in element" -> waitFor(client, sessionId,
            "document.querySelector(" + json(selector) + ") && document.querySelector("
                + json(selector) + ").innerText.includes(" + json(value) + ")");
        case "Press Enter" -> pressKey(client, sessionId, "Enter", 13);
        case "Check checkbox" -> setChecked(client, sessionId, selector, true);
        case "Uncheck checkbox" -> setChecked(client, sessionId, selector, false);
        case "Remove elements" -> evaluate(client, sessionId,
            "document.querySelectorAll(" + json(selector) + ").forEach(function(e){e.remove();})");
        case "Make all child elements visible" -> evaluate(client, sessionId,
            "(function(){var r=document.querySelector(" + json(selector) + ");if(!r)return;"
                + "r.querySelectorAll('*').forEach(function(e){"
                + "e.style.display='block';e.style.visibility='visible';e.style.opacity='1';"
                + "e.style.height='auto';e.style.maxHeight='none';e.style.overflow='visible';"
                + "});})()");
        default -> {
          // An operation the rebuild does not know is skipped rather than failing the check,
          // which is what happens when a newer interface writes a step an older engine reads.
        }
      }
    } catch (ProcessorExceptions.BrowserStepFailed e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProcessorExceptions.BrowserStepFailed(String.valueOf(e.getMessage()), stepNumber);
    }
  }

  private static void gotoUrl(CdpClient client, String sessionId, String url) {
    if (url == null || url.isBlank()) {
      return;
    }
    // A step's address is typed by whoever wrote the step and is not the watch's address, so
    // it has not been through the address rules yet. Without this a step is a way to make the
    // server read a local file or reach an address on its own network.
    boolean allowFile = Fields.truthy(System.getenv("ALLOW_FILE_URI"));
    boolean allowRestricted = Fields.truthy(System.getenv("ALLOW_IANA_RESTRICTED_ADDRESSES"));
    UrlSafety.Verdict verdict = UrlSafety.isFetchAllowed(url, allowFile, allowRestricted);
    if (!verdict.allowed()) {
      throw new ProcessorExceptions.PageUnloadable(verdict.reason(), 0);
    }
    client.send("Page.navigate", Map.of("url", url), sessionId);
    waitForLoad(client, sessionId);
  }

  public static void waitForLoad(CdpClient client, String sessionId) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      JsonNode result =
          client.send(
              "Runtime.evaluate",
              Map.of("expression", "document.readyState", "returnByValue", true),
              sessionId);
      String state = result.path("result").path("value").asText("");
      if (state.equals("complete") || state.equals("interactive")) {
        return;
      }
      sleep(100);
    }
  }

  private static void waitFor(CdpClient client, String sessionId, String expression) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      JsonNode result =
          client.send(
              "Runtime.evaluate",
              Map.of("expression", "!!(" + expression + ")", "returnByValue", true),
              sessionId);
      if (result.path("result").path("value").asBoolean(false)) {
        return;
      }
      sleep(200);
    }
    throw new IllegalStateException("timed out waiting for the page");
  }

  private static void setChecked(
      CdpClient client, String sessionId, String selector, boolean checked) {
    evaluate(client, sessionId,
        "(function(){var e=document.querySelector(" + json(selector) + ");if(!e)return;"
            + "e.checked=" + checked + ";"
            + "e.dispatchEvent(new Event('change',{bubbles:true}));})()");
  }

  private static void clickAt(CdpClient client, String sessionId, String value) {
    if (value == null || !COORDINATES.matcher(value).matches()) {
      return;
    }
    String[] parts = value.strip().split(",");
    int x = (int) Double.parseDouble(parts[0].strip());
    int y = (int) Double.parseDouble(parts[1].strip());
    Map<String, Object> down = new LinkedHashMap<>();
    down.put("type", "mousePressed");
    down.put("x", x);
    down.put("y", y);
    down.put("button", "left");
    down.put("clickCount", 1);
    client.send("Input.dispatchMouseEvent", down, sessionId);
    Map<String, Object> up = new LinkedHashMap<>(down);
    up.put("type", "mouseReleased");
    client.send("Input.dispatchMouseEvent", up, sessionId);
  }

  private static void pressKey(CdpClient client, String sessionId, String key, int code) {
    Map<String, Object> down = new LinkedHashMap<>();
    down.put("type", "keyDown");
    down.put("key", key);
    down.put("windowsVirtualKeyCode", code);
    down.put("nativeVirtualKeyCode", code);
    client.send("Input.dispatchKeyEvent", down, sessionId);
    Map<String, Object> up = new LinkedHashMap<>(down);
    up.put("type", "keyUp");
    client.send("Input.dispatchKeyEvent", up, sessionId);
  }

  public static JsonNode evaluate(CdpClient client, String sessionId, String expression) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("expression", expression);
    params.put("returnByValue", true);
    params.put("awaitPromise", true);
    return client.send("Runtime.evaluate", params, sessionId);
  }

  private static double parseSeconds(String value) {
    if (value == null || value.isBlank()) {
      return 1.0;
    }
    try {
      return Double.parseDouble(value.strip());
    } catch (NumberFormatException e) {
      return 1.0;
    }
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(Math.max(0, millis));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static String json(String value) {
    return PythonJson.dumpsCompact(PythonJson.MAPPER.valueToTree(value == null ? "" : value));
  }

  /** The name a step's operation is stored under, lower-cased for comparison. */
  public static boolean isKnownOperation(String operation) {
    for (String known : UI_CONFIG.keySet()) {
      if (known.toLowerCase(Locale.ROOT).equals(operation.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
