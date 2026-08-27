package io.akka.changedetection.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The call to a language model.
 *
 * <p>Every provider is reached over its own HTTP shape rather than through one library, so the
 * shapes are written out here: the four families the original's router supports differ in where
 * the system instruction goes, what the token counts are called, and where the text lands.
 *
 * <p>Failures are surfaced, not swallowed. A caller that wants a change to go through anyway
 * decides that for itself -- suppressing a notification because a model was unreachable would
 * be the worst possible failure for a tool whose job is to tell you something changed.
 */
public final class LlmClient {

  /** The cap for the calls that return a small JSON object. */
  public static final int JSON_RESPONSE_MAX_TOKENS = 400;

  public static final int DEFAULT_TIMEOUT = envInt("LLM_TIMEOUT", 300);

  /**
   * The deadline for an endpoint on the operator's own hardware.
   *
   * <p>Much longer than the cloud one because a local model spends minutes reading the prompt
   * before it produces its first token, and a five-minute deadline cuts it off mid-thought.
   */
  public static final int DEFAULT_LOCAL_TIMEOUT = envInt("LLM_LOCAL_TIMEOUT", 1800);

  public static final int DEFAULT_RETRIES = 3;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(30))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  private LlmClient() {}

  /** One message in the conversation. */
  public record Message(String role, String content) {}

  /** What came back, with the counts the provider reported. */
  public record Completion(String text, int totalTokens, int inputTokens, int outputTokens) {}

  /** Raised where the original lets the provider's own error out. */
  public static class LlmCallFailed extends RuntimeException {
    public LlmCallFailed(String message) {
      super(message);
    }

    public LlmCallFailed(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** What the request needs, so that the growing list of settings stays readable. */
  public static final class Request {
    public String model = "";
    public List<Message> messages = new ArrayList<>();
    public String apiKey = "";
    public String apiBase = "";
    public int timeoutSeconds = DEFAULT_TIMEOUT;
    public Integer maxTokens;
    public Map<String, Object> extraBody;
    public boolean debug;
  }

  public static Completion completion(Request request) {
    int maxTokens = request.maxTokens == null ? JSON_RESPONSE_MAX_TOKENS : request.maxTokens;
    Family family = familyOf(request.model);
    boolean strippedSampling = false;

    int attempt = 0;
    while (attempt < DEFAULT_RETRIES) {
      attempt++;
      HttpRequest httpRequest = build(family, request, maxTokens, !strippedSampling);
      HttpResponse<byte[]> response;
      try {
        response = CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
      } catch (HttpTimeoutException | ConnectException e) {
        if (attempt < DEFAULT_RETRIES) {
          continue;
        }
        throw new LlmCallFailed(
            "LLM call failed after "
                + DEFAULT_RETRIES
                + " attempts ("
                + request.timeoutSeconds
                + "s timeout) model='"
                + request.model
                + "' error="
                + e,
            e);
      } catch (IOException e) {
        if (attempt < DEFAULT_RETRIES) {
          continue;
        }
        throw new LlmCallFailed("LLM call failed: model='" + request.model + "' error=" + e, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LlmCallFailed("LLM call interrupted", e);
      }

      String body = new String(response.body(), StandardCharsets.UTF_8);
      if (response.statusCode() >= 400) {
        // A provider that will not accept a sampling setting says so as a bad request. The
        // settings are dropped and the call retried once, off the retry budget, because a
        // model that refuses them would otherwise never answer at all.
        String lower = body.toLowerCase(Locale.ROOT);
        if (!strippedSampling
            && (lower.contains("temperature")
                || lower.contains("top_p")
                || lower.contains("top_k"))) {
          strippedSampling = true;
          attempt--;
          continue;
        }
        throw new LlmCallFailed(
            "LLM call failed: model='"
                + request.model
                + "' error=HTTP "
                + response.statusCode()
                + " "
                + body);
      }

      try {
        return read(family, MAPPER.readTree(body));
      } catch (IOException e) {
        throw new LlmCallFailed("LLM call returned a reply that could not be read: " + e, e);
      }
    }
    throw new LlmCallFailed("LLM call failed: model='" + request.model + "'");
  }

  /** The shape a provider speaks, which the model name says. */
  enum Family {
    ANTHROPIC,
    GEMINI,
    OLLAMA,
    OPENAI
  }

  static Family familyOf(String model) {
    String name = model == null ? "" : model.strip();
    if (name.startsWith("claude") || name.startsWith("anthropic/")) {
      return Family.ANTHROPIC;
    }
    if (name.startsWith("gemini/") || name.startsWith("vertex_ai/gemini")) {
      return Family.GEMINI;
    }
    if (name.startsWith("ollama/") || name.startsWith("ollama_chat/")) {
      return Family.OLLAMA;
    }
    return Family.OPENAI;
  }

  /** The model as the provider names it, with the routing prefix removed. */
  static String bareModel(String model) {
    String name = model == null ? "" : model.strip();
    for (String prefix :
        List.of(
            "anthropic/",
            "gemini/",
            "vertex_ai/",
            "ollama_chat/",
            "ollama/",
            "openai/",
            "azure/")) {
      if (name.startsWith(prefix)) {
        return name.substring(prefix.length());
      }
    }
    if (name.startsWith("openrouter/")) {
      // OpenRouter names a model by its own vendor and model, so only the routing word goes.
      return name.substring("openrouter/".length());
    }
    return name;
  }

  static String endpointFor(Family family, Request request) {
    String base = request.apiBase == null ? "" : request.apiBase.strip();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return switch (family) {
      case ANTHROPIC -> (base.isEmpty() ? "https://api.anthropic.com" : base) + "/v1/messages";
      case GEMINI ->
          (base.isEmpty() ? "https://generativelanguage.googleapis.com/v1beta" : base)
              + "/models/"
              + bareModel(request.model)
              + ":generateContent";
      case OLLAMA -> (base.isEmpty() ? "http://localhost:11434" : base) + "/api/chat";
      case OPENAI -> {
        String root = base;
        if (root.isEmpty()) {
          root =
              request.model.startsWith("openrouter/")
                  ? "https://openrouter.ai/api/v1"
                  : "https://api.openai.com/v1";
        }
        // An endpoint given with the path already on it is used as given, which is what an
        // operator pointing at their own server writes.
        yield root.endsWith("/chat/completions") ? root : root + "/chat/completions";
      }
    };
  }

  private static HttpRequest build(
      Family family, Request request, int maxTokens, boolean withSampling) {
    ObjectNode body = MAPPER.createObjectNode();
    String endpoint = endpointFor(family, request);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .timeout(Duration.ofSeconds(request.timeoutSeconds))
            .header("Content-Type", "application/json");

    switch (family) {
      case ANTHROPIC -> {
        body.put("model", bareModel(request.model));
        body.put("max_tokens", maxTokens);
        if (withSampling) {
          body.put("temperature", 0);
        }
        String system = systemOf(request.messages);
        if (!system.isEmpty()) {
          body.put("system", system);
        }
        body.set("messages", conversationOf(request.messages));
        builder.header("anthropic-version", "2023-06-01");
        if (!request.apiKey.isEmpty()) {
          builder.header("x-api-key", request.apiKey);
        }
      }
      case GEMINI -> {
        ArrayNode contents = MAPPER.createArrayNode();
        for (Message message : request.messages) {
          if (message.role().equals("system")) {
            continue;
          }
          ObjectNode entry = contents.addObject();
          entry.put("role", message.role().equals("assistant") ? "model" : "user");
          entry.putArray("parts").addObject().put("text", message.content());
        }
        body.set("contents", contents);
        String system = systemOf(request.messages);
        if (!system.isEmpty()) {
          ObjectNode instruction = body.putObject("systemInstruction");
          instruction.putArray("parts").addObject().put("text", system);
        }
        ObjectNode generation = body.putObject("generationConfig");
        if (withSampling) {
          generation.put("temperature", 0);
        }
        generation.put("maxOutputTokens", maxTokens);
        mergeExtra(body, request.extraBody);
        if (!request.apiKey.isEmpty()) {
          builder.header("x-goog-api-key", request.apiKey);
        }
      }
      case OLLAMA -> {
        body.put("model", bareModel(request.model));
        body.set("messages", allMessages(request.messages));
        body.put("stream", false);
        ObjectNode options = body.putObject("options");
        if (withSampling) {
          options.put("temperature", 0);
        }
        options.put("num_predict", maxTokens);
        if (!request.apiKey.isEmpty()) {
          builder.header("Authorization", "Bearer " + request.apiKey);
        }
      }
      case OPENAI -> {
        body.put("model", bareModel(request.model));
        body.set("messages", allMessages(request.messages));
        if (withSampling) {
          body.put("temperature", 0);
        }
        body.put("max_tokens", maxTokens);
        mergeExtra(body, request.extraBody);
        if (!request.apiKey.isEmpty()) {
          builder.header("Authorization", "Bearer " + request.apiKey);
        }
      }
    }

    String payload;
    try {
      payload = MAPPER.writeValueAsString(body);
    } catch (IOException e) {
      throw new LlmCallFailed("could not build the request", e);
    }
    return builder
        .uri(URI.create(endpoint))
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        .build();
  }

  /**
   * Folds extra settings into the request body.
   *
   * <p>Merged rather than assigned, one level down as well: the thinking setting arrives inside
   * the same object that already carries the token cap, and replacing it wholesale would drop
   * the cap and let a reasoning model run until it hit the provider's own ceiling.
   */
  private static void mergeExtra(ObjectNode body, Map<String, Object> extra) {
    if (extra == null || extra.isEmpty()) {
      return;
    }
    ObjectNode converted = MAPPER.valueToTree(extra);
    converted
        .fieldNames()
        .forEachRemaining(
            name -> {
              JsonNode incoming = converted.get(name);
              JsonNode existing = body.get(name);
              if (existing != null && existing.isObject() && incoming.isObject()) {
                deepMerge((ObjectNode) existing, (ObjectNode) incoming);
              } else {
                body.set(name, incoming);
              }
            });
  }

  private static void deepMerge(ObjectNode target, ObjectNode source) {
    source
        .fieldNames()
        .forEachRemaining(
            name -> {
              JsonNode incoming = source.get(name);
              JsonNode existing = target.get(name);
              if (existing != null && existing.isObject() && incoming.isObject()) {
                deepMerge((ObjectNode) existing, (ObjectNode) incoming);
              } else {
                target.set(name, incoming);
              }
            });
  }

  private static String systemOf(List<Message> messages) {
    StringBuilder sb = new StringBuilder();
    for (Message message : messages) {
      if (message.role().equals("system")) {
        if (sb.length() > 0) {
          sb.append("\n\n");
        }
        sb.append(message.content());
      }
    }
    return sb.toString();
  }

  private static ArrayNode conversationOf(List<Message> messages) {
    ArrayNode array = MAPPER.createArrayNode();
    for (Message message : messages) {
      if (message.role().equals("system")) {
        continue;
      }
      ObjectNode entry = array.addObject();
      entry.put("role", message.role());
      entry.put("content", message.content());
    }
    return array;
  }

  private static ArrayNode allMessages(List<Message> messages) {
    ArrayNode array = MAPPER.createArrayNode();
    for (Message message : messages) {
      ObjectNode entry = array.addObject();
      entry.put("role", message.role());
      entry.put("content", message.content());
    }
    return array;
  }

  static Completion read(Family family, JsonNode response) {
    return switch (family) {
      case ANTHROPIC -> {
        StringBuilder text = new StringBuilder();
        JsonNode content = response.path("content");
        for (JsonNode part : content) {
          text.append(part.path("text").asText(""));
        }
        int input = response.path("usage").path("input_tokens").asInt(0);
        int output = response.path("usage").path("output_tokens").asInt(0);
        yield new Completion(text.toString(), input + output, input, output);
      }
      case GEMINI -> {
        StringBuilder text = new StringBuilder();
        for (JsonNode part :
            response.path("candidates").path(0).path("content").path("parts")) {
          text.append(part.path("text").asText(""));
        }
        JsonNode usage = response.path("usageMetadata");
        int input = usage.path("promptTokenCount").asInt(0);
        int output = usage.path("candidatesTokenCount").asInt(0);
        int total = usage.path("totalTokenCount").asInt(input + output);
        yield new Completion(text.toString(), total, input, output);
      }
      case OLLAMA -> {
        String text = response.path("message").path("content").asText("");
        int input = response.path("prompt_eval_count").asInt(0);
        int output = response.path("eval_count").asInt(0);
        yield new Completion(text, input + output, input, output);
      }
      case OPENAI -> {
        JsonNode message = response.path("choices").path(0).path("message");
        String text = message.path("content").asText("");
        if (text.isEmpty()) {
          // Some endpoints put the answer in parts rather than in one string.
          StringBuilder parts = new StringBuilder();
          for (JsonNode part : message.path("parts")) {
            parts.append(part.path("text").asText(""));
          }
          text = parts.toString().strip();
        }
        JsonNode usage = response.path("usage");
        int input = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(input + output);
        yield new Completion(text, total, input, output);
      }
    };
  }

  private static int envInt(String variable, int fallback) {
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

  /** The settings a message carries, so a caller can build one without a builder class. */
  public static Map<String, Object> thinkingBudget(String model, int budget) {
    // Only the one provider takes the setting in the request body; the others express it in
    // the model name or not at all, so naming it elsewhere would be rejected outright.
    if (!model.startsWith("gemini/")) {
      return null;
    }
    Map<String, Object> thinking = new LinkedHashMap<>();
    thinking.put("thinkingBudget", budget);
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("thinkingConfig", thinking);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generationConfig", config);
    return body;
  }
}
