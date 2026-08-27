package io.akka.changedetection.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.model.UrlSafety;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The two questions the settings page asks a provider before anything is saved.
 *
 * <p>Which models it offers, and whether a call to it works at all. Both go out to whoever the
 * operator named, so both are refused for an address that would reach inside the network the
 * service is running in -- the same rule the stored setting is checked against, applied to the
 * value being tried rather than the one already saved.
 */
public final class ProviderProbe {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  /** What a model name is prefixed with so the rest of the system knows who serves it. */
  private static final Map<String, String> PREFIXES = new LinkedHashMap<>();

  static {
    PREFIXES.put("gemini", "gemini/");
    PREFIXES.put("ollama", "ollama/");
    PREFIXES.put("openrouter", "openrouter/");
    PREFIXES.put("openai_compatible", "openai/");
  }

  private ProviderProbe() {}

  /** Why this address may not be called, or null when it may. */
  public static String apiBaseRefusal(String apiBase) {
    if (apiBase == null || apiBase.strip().isEmpty()) {
      return null;
    }
    return UrlSafety.whyApiBaseIsRefused(apiBase.strip());
  }

  /**
   * The models this provider says it has, named the way the rest of the system names them.
   *
   * <p>A provider that answers with an error answers with its own words, because "no models"
   * and "your key is wrong" are the two things an operator needs to tell apart and they look
   * identical from an empty list.
   */
  public static List<String> availableModels(String provider, String apiKey, String apiBase) {
    String prefix = PREFIXES.getOrDefault(provider, "");
    String endpoint = modelsEndpoint(provider, apiBase);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(30)).GET();
    if (apiKey != null && !apiKey.isEmpty()) {
      if ("anthropic".equals(provider)) {
        builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
      } else {
        builder.header("Authorization", "Bearer " + apiKey);
      }
    }

    HttpResponse<byte[]> response;
    try {
      response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (Exception e) {
      throw new LlmClient.LlmCallFailed(String.valueOf(e.getMessage()), e);
    }
    String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    if (response.statusCode() >= 400) {
      throw new LlmClient.LlmCallFailed(providerMessage(body, response.statusCode()));
    }
    JsonNode parsed;
    try {
      parsed = MAPPER.readTree(body);
    } catch (Exception e) {
      throw new LlmClient.LlmCallFailed("Provider returned a reply that could not be read");
    }

    List<String> names = new ArrayList<>();
    for (String field : List.of("data", "models")) {
      JsonNode list = parsed.get(field);
      if (list != null && list.isArray()) {
        for (JsonNode entry : list) {
          JsonNode id = entry.has("id") ? entry.get("id") : entry.get("name");
          if (id != null && id.isTextual()) {
            names.add(id.asText());
          }
        }
      }
    }
    TreeSet<String> out = new TreeSet<>();
    for (String name : names) {
      out.add(name.startsWith(prefix) ? name : prefix + name);
    }
    return new ArrayList<>(out);
  }

  /** What a provider said when asked to answer one short prompt. */
  public record TestReply(String text, int totalTokens) {}

  public static TestReply testConnection(
      Map<String, Object> storedConfig, String model, String apiKey, String apiBase) {
    Map<String, Object> config = new LinkedHashMap<>(storedConfig);
    config.put("model", model);
    config.put("api_base", apiBase);

    LlmClient.Request request = new LlmClient.Request();
    request.model = model;
    request.messages = List.of(new LlmClient.Message("user", "Respond with just the word: ready"));
    request.apiKey = apiKey == null ? "" : apiKey;
    request.apiBase = apiBase == null ? "" : apiBase;
    request.timeoutSeconds = Evaluator.resolveTimeout(config);
    // The same headroom a real call gets, so a local model that needs room to think is not
    // failed by a test the production path would have passed.
    request.maxTokens = Evaluator.applyLocalTokenMultiplier(200, config);
    LlmClient.Completion completion = LlmClient.completion(request);
    return new TestReply(completion.text().strip(), completion.totalTokens());
  }

  static String modelsEndpoint(String provider, String apiBase) {
    String base = apiBase == null ? "" : apiBase.strip();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (!base.isEmpty()) {
      return "ollama".equals(provider) ? base + "/api/tags" : base + "/models";
    }
    return switch (provider.toLowerCase(Locale.ROOT)) {
      case "anthropic" -> "https://api.anthropic.com/v1/models";
      case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models";
      case "ollama" -> "http://localhost:11434/api/tags";
      case "openrouter" -> "https://openrouter.ai/api/v1/models";
      default -> "https://api.openai.com/v1/models";
    };
  }

  /** The provider's own complaint where it made one, rather than a status code by itself. */
  static String providerMessage(String body, int status) {
    try {
      JsonNode parsed = MAPPER.readTree(body);
      JsonNode error = parsed.get("error");
      if (error != null) {
        JsonNode message = error.get("message");
        if (message != null && message.isTextual()) {
          return message.asText();
        }
      }
      JsonNode message = parsed.get("message");
      if (message != null && message.isTextual()) {
        return message.asText();
      }
    } catch (Exception e) {
      // Not a reply shaped like an error: the status and the body are all there is.
    }
    String trimmed = body.strip();
    if (trimmed.length() > 500) {
      trimmed = trimmed.substring(0, 500);
    }
    return "HTTP " + status + (trimmed.isEmpty() ? "" : " " + trimmed);
  }
}
