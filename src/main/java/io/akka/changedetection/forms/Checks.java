package io.akka.changedetection.forms;

import io.akka.changedetection.application.TemplateEngine;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.jinja.Node;
import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.jinja.TemplateParser;
import io.akka.changedetection.jinja.Undeclared;
import io.akka.changedetection.model.UrlSafety;
import io.akka.changedetection.notification.NotificationContext;
import io.akka.changedetection.notification.NotificationHandler;
import io.akka.changedetection.text.JsonFilter;
import io.akka.changedetection.text.XPathFilter;
import io.akka.changedetection.text.PyRegex;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The rules a submitted value has to satisfy before it is stored. */
public final class Checks {

  /** A plain hex colour and nothing else; the value goes into a stylesheet. */
  public static final Pattern CSS_HEX_COLOUR = Pattern.compile("^#(?:[0-9a-fA-F]{3}){1,2}$");

  /** Only asked whether an address can be delivered to, never used to deliver. */
  private static final NotificationHandler DELIVERY =
      new NotificationHandler(TemplateEngine.notifications());

  /**
   * The complaint about the first address that cannot be delivered to, or nothing when all can.
   *
   * <p>The same rule the form applies, reached without a form: a caller sending addresses over
   * the programmatic interface gets the identical refusal a person typing them would.
   */
  public static String notificationUrlProblem(java.util.List<String> urls) {
    for (String raw : urls) {
      String configured = raw.strip();
      if (configured.isEmpty()) {
        continue;
      }
      Map<String, Object> tokens = randomTokens();
      Environment environment = TemplateEngine.notifications();
      for (Map.Entry<String, Object> entry : tokens.entrySet()) {
        environment.putGlobal(entry.getKey(), entry.getValue());
      }
      String address;
      try {
        address = environment.renderString(configured, new LinkedHashMap<>()).strip();
      } catch (RuntimeException e) {
        return "'" + configured + "' is not a valid AppRise URL.";
      }
      if (address.startsWith("#")) {
        continue;
      }
      if (!DELIVERY.canDeliverTo(address)) {
        return "'" + address + "' is not a valid AppRise URL.";
      }
    }
    return null;
  }

  private Checks() {}

  /** Skips the remaining rules when nothing was entered. */
  private static boolean empty(Field field) {
    Object data = field.data();
    if (data == null) {
      return true;
    }
    if (data instanceof CharSequence text) {
      return text.length() == 0;
    }
    if (data instanceof List<?> list) {
      return list.isEmpty();
    }
    if (data instanceof Map<?, ?> map) {
      return map.isEmpty();
    }
    return false;
  }

  /** The address a watch may be pointed at. */
  public static Field.Check url() {
    return field -> {
      if (!UrlSafety.isSafeValidUrl(PyValue.asString(field.data()), false)) {
        field.fail("Watch protocol is not permitted or invalid URL format");
      }
    };
  }

  /** Anything that can be parsed as an address with a scheme and a host. */
  public static Field.Check simpleUrl(String message) {
    return field -> {
      String value = PyValue.asString(field.data()).strip();
      if (value.isEmpty()) {
        return;
      }
      try {
        URI parsed = URI.create(value);
        if (parsed.getScheme() == null || parsed.getAuthority() == null) {
          field.fail(message);
        }
      } catch (IllegalArgumentException e) {
        field.fail(message);
      }
    };
  }

  public static Field.Check startsWithPattern(String regex, String message) {
    Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    return field -> {
      Object data = field.data();
      if (data == null) {
        return;
      }
      List<String> lines = new ArrayList<>();
      if (data instanceof List<?> list) {
        for (Object item : list) {
          lines.add(PyValue.asString(item));
        }
      } else {
        String text = PyValue.asString(data);
        if (text.isEmpty()) {
          return;
        }
        lines.addAll(List.of(text.split("\n", -1)));
      }
      for (String line : lines) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
          continue;
        }
        if (!pattern.matcher(stripped).lookingAt()) {
          field.fail(message);
          return;
        }
      }
    };
  }

  public static Field.Check matching(Pattern pattern, String message) {
    return field -> {
      String value = PyValue.asString(field.data());
      if (value.isEmpty()) {
        return;
      }
      if (!pattern.matcher(value).lookingAt()) {
        field.fail(message);
      }
    };
  }

  public static Field.Check numberRange(Double minimum, Double maximum, String message) {
    return field -> {
      Object data = field.data();
      if (data == null) {
        return;
      }
      double value;
      if (data instanceof Number number) {
        value = number.doubleValue();
      } else {
        String text = PyValue.asString(data).strip();
        if (text.isEmpty()) {
          return;
        }
        try {
          value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
          return;
        }
      }
      if (minimum != null && value < minimum) {
        field.fail(message);
        return;
      }
      if (maximum != null && value > maximum) {
        field.fail(message);
      }
    };
  }

  public static Field.Check maximumLength(int maximum, String message) {
    return field -> {
      String value = PyValue.asString(field.data());
      if (value.length() > maximum) {
        field.fail(message);
      }
    };
  }

  public static Field.Check timezoneName() {
    return field -> {
      String value = PyValue.asString(field.data());
      if (!value.isEmpty() && !Choices.isKnownTimezone(value)) {
        field.fail("Not a valid timezone name");
      }
    };
  }

  /** A single expression, which is used whole rather than a line at a time. */
  public static Field.Check singleRegex() {
    return field -> {
      String value = PyValue.asString(field.data());
      try {
        PyRegex.compile(value);
      } catch (RuntimeException e) {
        field.fail("RegEx '" + value + "' is not a valid regular expression.");
      }
    };
  }

  /**
   * A list of rules, of which only the ones written as expressions are checked.
   *
   * <p>A plain line is matched literally rather than compiled, so a line full of brackets is a
   * perfectly good rule and must not be rejected as a broken expression.
   */
  public static Field.Check regexList() {
    return field -> {
      for (Object item : PyValue.iterate(field.data())) {
        String line = PyValue.asString(item);
        if (!PyRegex.isPerlStyle(line)) {
          continue;
        }
        try {
          PyRegex.compile(PyRegex.perlStyleToOptions(line));
        } catch (RuntimeException e) {
          field.fail("RegEx '" + line + "' is not a valid regular expression.");
        }
      }
    };
  }

  /** A list of selectors, each checked against the language its prefix names. */
  public static Field.Check selectors(boolean allowXpath, boolean allowJson) {
    return field -> {
      List<String> lines = new ArrayList<>();
      Object data = field.data();
      if (data instanceof CharSequence text) {
        lines.add(text.toString());
      } else {
        for (Object item : PyValue.iterate(data)) {
          lines.add(PyValue.asString(item));
        }
      }
      for (String line : lines) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
          // The original stops at the first blank line rather than skipping it, so a blank
          // first line silently accepts everything below it.
          return;
        }
        if (stripped.startsWith("/") || stripped.startsWith("xpath:")) {
          if (!allowXpath) {
            field.fail("XPath not permitted in this field!");
            continue;
          }
          String expression = stripped.replace("xpath:", "").strip();
          try {
            XPathFilter.validateExpression(expression, false);
          } catch (RuntimeException e) {
            field.fail(
                "'" + line + "' is not a valid XPath expression. (" + e.getMessage() + ")");
          }
          continue;
        }
        if (stripped.startsWith("xpath1:")) {
          if (!allowXpath) {
            field.fail("XPath not permitted in this field!");
            continue;
          }
          String expression = stripped.replaceFirst("^xpath1:", "").strip();
          try {
            XPathFilter.validateExpression(expression, true);
          } catch (RuntimeException e) {
            field.fail(
                "'" + line + "' is not a valid XPath expression. (" + e.getMessage() + ")");
          }
          continue;
        }
        if (stripped.contains("json:")) {
          if (!allowJson) {
            field.fail("JSONPath not permitted in this field!");
            continue;
          }
          String expression = stripped.replace("json:", "");
          try {
            JsonFilter.validateJsonPath(expression);
          } catch (RuntimeException e) {
            field.fail(
                "'" + expression + "' is not a valid JSONPath expression. ("
                    + e.getMessage() + ")");
          }
        }
        if (stripped.contains("jq:")) {
          String expression = stripped.replace("jq:", "");
          try {
            JsonFilter.validateJqProgramme(expression);
          } catch (RuntimeException e) {
            field.fail(
                "'" + expression + "' is not a valid jq expression. (" + e.getMessage() + ")");
          }
        }
      }
    };
  }

  /**
   * A notification body, title or address list, checked as a template.
   *
   * <p>Checked with every token present but random, so a body that reads a token it was never
   * going to be given is caught here rather than at the moment a change is found.
   */
  public static Field.Check jinjaTemplate(Map<String, Object> extraTokens) {
    return field -> {
      Object data = field.data();
      String joined;
      if (data instanceof List<?> list) {
        List<String> parts = new ArrayList<>();
        for (Object item : list) {
          parts.add(PyValue.asString(item));
        }
        joined = String.join(" ", parts);
      } else {
        joined = PyValue.asString(data);
      }
      Environment environment = TemplateEngine.notifications();
      Map<String, Object> tokens = randomTokens();
      if (extraTokens != null) {
        tokens.putAll(extraTokens);
      }
      for (Map.Entry<String, Object> entry : tokens.entrySet()) {
        environment.putGlobal(entry.getKey(), entry.getValue());
      }
      try {
        environment.renderString(joined, new LinkedHashMap<>());
      } catch (RuntimeException e) {
        field.fail("This is not a valid Jinja2 template: " + e.getMessage());
        return;
      }
      Node.Template parsed;
      try {
        parsed = TemplateParser.parse(joined, "<string>");
      } catch (RuntimeException e) {
        field.fail("This is not a valid Jinja2 template: " + e.getMessage());
        return;
      }
      Set<String> undeclared = Undeclared.in(parsed);
      undeclared.removeAll(tokens.keySet());
      undeclared.removeAll(environment.globals().keySet());
      if (!undeclared.isEmpty()) {
        field.fail(
            "The following tokens used in the notification are not valid: "
                + String.join(", ", undeclared));
      }
    };
  }

  /** Each address is one a notification can actually be sent to. */
  public static Field.Check notificationTargets() {
    return field -> {
      Map<String, Object> tokens = randomTokens();
      for (Object item : PyValue.iterate(field.data())) {
        String configured = PyValue.asString(item).strip();
        Environment environment = TemplateEngine.notifications();
        for (Map.Entry<String, Object> entry : tokens.entrySet()) {
          environment.putGlobal(entry.getKey(), entry.getValue());
        }
        String address;
        try {
          address = environment.renderString(configured, new LinkedHashMap<>()).strip();
        } catch (RuntimeException e) {
          field.fail("'" + configured + "' is not a valid AppRise URL.");
          continue;
        }
        if (address.startsWith("#")) {
          continue;
        }
        if (!DELIVERY.canDeliverTo(address)) {
          field.fail("'" + address + "' is not a valid AppRise URL.");
        }
      }
    };
  }

  /**
   * Where the AI evaluation may be pointed.
   *
   * <p>The address is fetched by the server, so an operator who could name a private one could
   * read whatever is reachable from inside the network.
   */
  public static Field.Check llmApiBase() {
    return field -> {
      String value = PyValue.asString(field.data()).strip();
      if (value.isEmpty()) {
        return;
      }
      String refusal = UrlSafety.whyApiBaseIsRefused(value);
      if (refusal != null) {
        field.fail(refusal);
      }
    };
  }

  public static Field.Check boundingBox() {
    Pattern shape = Pattern.compile("^\\d+,\\d+,\\d+,\\d+$");
    return field -> {
      String value = PyValue.asString(field.data());
      if (value.isEmpty()) {
        return;
      }
      if (value.length() > 100) {
        field.fail("Bounding box value is too long");
        return;
      }
      if (!shape.matcher(value).matches()) {
        field.fail("Bounding box must be in format: x,y,width,height (integers only)");
        return;
      }
      for (String part : value.split(",")) {
        int number = Integer.parseInt(part);
        if (number < 0) {
          field.fail("Bounding box values must be non-negative");
          return;
        }
        if (number > 10000) {
          field.fail("Bounding box values are too large");
          return;
        }
      }
    };
  }

  public static Field.Check selectionMode() {
    return field -> {
      String value = PyValue.asString(field.data());
      if (value.isEmpty()) {
        return;
      }
      if (!value.equals("element") && !value.equals("draw")) {
        field.fail("Selection mode must be either \"element\" or \"draw\"");
      }
    };
  }

  /**
   * Every token with a value, so a template can be exercised without a real change.
   *
   * <p>The identifiers and the difference tokens keep their real shape, because a body that
   * formats them would otherwise be checked against something that could never occur.
   */
  public static Map<String, Object> randomTokens() {
    Map<String, Object> tokens = new LinkedHashMap<>(NotificationContext.emptyContext());
    int counter = 0;
    for (Map.Entry<String, Object> entry : tokens.entrySet()) {
      String key = entry.getKey();
      if (key.equals("uuid")
          || key.equals("time")
          || key.equals("watch_uuid")
          || key.equals("change_datetime")
          || key.startsWith("diff")) {
        continue;
      }
      entry.setValue("RANDOM-PLACEHOLDER-" + placeholder(counter++));
    }
    return tokens;
  }

  private static String placeholder(int index) {
    // Fixed rather than random: the value is only ever compared for presence, and a fixed one
    // makes a rejected template reproducible.
    String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    StringBuilder sb = new StringBuilder();
    int value = index + 1;
    for (int position = 0; position < 12; position++) {
      sb.append(alphabet.charAt((value * (position + 7)) % alphabet.length()));
    }
    return sb.toString();
  }

  static String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }
}
