package io.akka.changedetection.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The command line, as the original reads it.
 *
 * <p>Serving is started by the runtime rather than by a main of ours, so the flags are parsed
 * here and read by whatever needs them. A deployment that cannot pass a command line -- a
 * container image, a managed platform -- can pass the same words in
 * {@code CHANGEDETECTION_ARGS} instead, which is the only way the two shapes can be given the
 * same treatment.
 */
public final class Options {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** What the caller asked for, with the two answers that end the run before it starts. */
  public record Parsed(
      boolean help,
      boolean version,
      boolean ssl,
      String host,
      int port,
      String datastorePath,
      boolean createDatastoreDir,
      String logLevel,
      Boolean allPaused,
      List<String> urls,
      Map<Integer, Map<String, Object>> urlOptions,
      String recheck,
      int recheckRepeatCount,
      boolean batchMode,
      String error) {

    Parsed withHelp() {
      return new Parsed(true, false, ssl, host, port, datastorePath, createDatastoreDir,
          logLevel, allPaused, urls, urlOptions, recheck, recheckRepeatCount, batchMode, null);
    }

    Parsed withVersion() {
      return new Parsed(false, true, ssl, host, port, datastorePath, createDatastoreDir,
          logLevel, allPaused, urls, urlOptions, recheck, recheckRepeatCount, batchMode, null);
    }

    Parsed withError(String message) {
      return new Parsed(false, false, ssl, host, port, datastorePath, createDatastoreDir,
          logLevel, allPaused, urls, urlOptions, recheck, recheckRepeatCount, batchMode, message);
    }
  }

  private static volatile Parsed current = parse(new String[0]);

  private Options() {}

  public static Parsed current() {
    return current;
  }

  public static boolean batchMode() {
    return current.batchMode();
  }

  /** Reads the flags a deployment passed in the environment, where it could not pass argv. */
  public static void applyFromEnvironment() {
    String packed = System.getenv("CHANGEDETECTION_ARGS");
    if (packed == null || packed.isBlank()) {
      current = parse(new String[0]);
      return;
    }
    current = parse(split(packed));
  }

  public static void apply(String[] argv) {
    current = parse(argv);
  }

  /** Splits a packed command line on spaces, honouring quotes so a JSON option survives. */
  static String[] split(String packed) {
    List<String> words = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    char quote = 0;
    for (int index = 0; index < packed.length(); index++) {
      char c = packed.charAt(index);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          word.append(c);
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (Character.isWhitespace(c)) {
        if (word.length() > 0) {
          words.add(word.toString());
          word.setLength(0);
        }
        continue;
      }
      word.append(c);
    }
    if (word.length() > 0) {
      words.add(word.toString());
    }
    return words.toArray(new String[0]);
  }

  public static Parsed parse(String[] argv) {
    for (String argument : argv) {
      if (argument.equals("--help") || argument.equals("-help")) {
        return blank().withHelp();
      }
      if (argument.equals("--version") || argument.equals("-v")) {
        return blank().withVersion();
      }
    }

    boolean ssl = false;
    String host = envOr("LISTEN_HOST", "0.0.0.0").strip();
    int port = envInt("PORT", 5000);
    String datastorePath = defaultDatastorePath();
    boolean createDatastoreDir = false;
    String logLevel = envOr("LOGGER_LEVEL", "DEBUG").toUpperCase(Locale.ROOT);
    Boolean allPaused = null;
    List<String> urls = new ArrayList<>();
    Map<Integer, Map<String, Object>> urlOptions = new LinkedHashMap<>();
    String recheck = null;
    int recheckRepeatCount = 1;
    boolean batchMode = false;

    List<String> remaining = new ArrayList<>();
    int index = 0;
    while (index < argv.length) {
      String argument = argv[index];

      if (argument.equals("-u") && index + 1 < argv.length) {
        urls.add(argv[index + 1]);
        index += 2;
        continue;
      }
      if (argument.startsWith("-u") && argument.length() > 2 && isDigits(argument.substring(2))) {
        int position = Integer.parseInt(argument.substring(2));
        if (index + 1 < argv.length) {
          try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(argv[index + 1], Map.class);
            urlOptions.put(position, parsed);
          } catch (Exception e) {
            return blank()
                .withError(
                    "Error: Invalid JSON for " + argument + ": " + argv[index + 1] + "\n"
                        + "JSON decode error: " + e.getMessage());
          }
          index += 2;
          continue;
        }
      }
      if (argument.equals("-r") && index + 1 < argv.length) {
        recheck = argv[index + 1];
        if (index + 2 < argv.length && isDigits(argv[index + 2])) {
          recheckRepeatCount = Integer.parseInt(argv[index + 2]);
          if (recheckRepeatCount < 1) {
            return blank()
                .withError(
                    "Error: Repeat count must be at least 1, got " + recheckRepeatCount);
          }
          index += 3;
        } else {
          index += 2;
        }
        continue;
      }
      if (argument.equals("-b")) {
        batchMode = true;
        index++;
        continue;
      }
      remaining.add(argument);
      index++;
    }

    // The flags the original hands to its option parser, and only those: a flag it documents
    // but does not list -- the snapshot cleanup -- is refused here exactly as it is there.
    String accepted = "6Csd:h:p:l:P:";
    int position = 0;
    while (position < remaining.size()) {
      String argument = remaining.get(position);
      if (!argument.startsWith("-") || argument.length() < 2) {
        position++;
        continue;
      }
      char flag = argument.charAt(1);
      int at = accepted.indexOf(flag);
      if (at < 0) {
        return blank().withError("Error: option -" + flag + " not recognized");
      }
      boolean takesValue = at + 1 < accepted.length() && accepted.charAt(at + 1) == ':';
      String value = null;
      if (takesValue) {
        if (argument.length() > 2) {
          value = argument.substring(2);
        } else if (position + 1 < remaining.size()) {
          value = remaining.get(++position);
        } else {
          return blank().withError("Error: option -" + flag + " requires argument");
        }
      }
      switch (flag) {
        case 's' -> ssl = true;
        case 'h' -> host = value;
        case 'p' -> port = Integer.parseInt(value);
        case 'd' -> datastorePath = value;
        case 'C' -> createDatastoreDir = true;
        case 'l' -> logLevel = value.toUpperCase(Locale.ROOT);
        case 'P' -> {
          Boolean parsed = truthy(value);
          if (parsed == null) {
            return blank()
                .withError(
                    "Error: Invalid value for -P option: " + value + "\n"
                        + "Expected: true, false, yes, no, 1, or 0");
          }
          allPaused = parsed;
        }
        default -> {
          // '6' selects the address family and changes nothing this rebuild does.
        }
      }
      position++;
    }

    return new Parsed(
        false,
        false,
        ssl,
        host,
        port,
        datastorePath,
        createDatastoreDir,
        logLevel,
        allPaused,
        urls,
        urlOptions,
        recheck,
        recheckRepeatCount,
        batchMode,
        null);
  }

  static Parsed blank() {
    return new Parsed(
        false,
        false,
        false,
        "0.0.0.0",
        5000,
        defaultDatastorePath(),
        false,
        "DEBUG",
        null,
        new ArrayList<>(),
        new LinkedHashMap<>(),
        null,
        1,
        false,
        null);
  }

  static String defaultDatastorePath() {
    String configured = System.getenv("DATASTORE_PATH");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      String appData = System.getenv("APPDATA");
      if (appData != null && !appData.isBlank()) {
        return appData + "\\changedetection.io";
      }
    }
    return "../datastore";
  }

  public static String helpText() {
    return String.join(
        "\n",
        "Usage: changedetection.py [options]",
        "",
        "Standard options:",
        "  -s                SSL enable",
        "  -h HOST           Listen host (default: 0.0.0.0)",
        "  -p PORT           Listen port (default: 5000)",
        "  -d PATH           Datastore path",
        "  -l LEVEL          Log level (TRACE, DEBUG, INFO, SUCCESS, WARNING, ERROR, CRITICAL)",
        "  -c                Cleanup unused snapshots",
        "  -C                Create datastore directory if it doesn't exist",
        "  -P true/false     Set all watches paused (true) or active (false)",
        "",
        "Add URLs on startup:",
        "  -u URL            Add URL to watch (can be used multiple times)",
        "  -u0 'JSON'        Set options for first -u URL (e.g. '{\"processor\":\"text_json_diff\"}')",
        "  -u1 'JSON'        Set options for second -u URL (0-indexed)",
        "  -u2 'JSON'        Set options for third -u URL, etc.",
        "                    Available options: processor, fetch_backend, headers, method, etc.",
        "                    See model/Watch.py for all available options",
        "",
        "Recheck on startup:",
        "  -r all            Queue all watches for recheck on startup",
        "  -r UUID,...       Queue specific watches (comma-separated UUIDs)",
        "  -r all N          Queue all watches, wait for completion, repeat N times",
        "  -r UUID,... N     Queue specific watches, wait for completion, repeat N times",
        "",
        "Batch mode:",
        "  -b                Run in batch mode (process queue then exit)",
        "                    Useful for CI/CD, cron jobs, or one-time checks",
        "                    NOTE: Batch mode checks if Flask is running and aborts if port is in use",
        "                    Use -p PORT to specify a different port if needed",
        "");
  }

  static boolean isDigits(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      if (!Character.isDigit(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  static Boolean truthy(String value) {
    String lower = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "y", "yes", "t", "true", "on", "1" -> Boolean.TRUE;
      case "n", "no", "f", "false", "off", "0" -> Boolean.FALSE;
      default -> null;
    };
  }

  static String envOr(String variable, String fallback) {
    String value = System.getenv(variable);
    return value == null || value.isBlank() ? fallback : value;
  }

  static int envInt(String variable, int fallback) {
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
