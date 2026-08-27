package io.akka.changedetection.notification;

import io.akka.changedetection.text.PythonJson;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Delivery to something that speaks over the web.
 *
 * <p>The address's scheme says both where to send and in what shape: one scheme posts a form,
 * one posts a document, one fetches with the content in the query. The distinction matters
 * because the receiving end is usually somebody else's endpoint that only accepts one of them.
 *
 * <p>Anything written on the address after a question mark is sent as an extra field, and
 * anything written with a leading plus is sent as a header. That is how an address alone can
 * carry an authorisation token without the system needing to know about that service.
 */
public final class WebhookSender implements Sender {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

  @Override
  public List<String> schemes() {
    return List.of("post", "posts", "json", "jsons", "form", "forms", "get", "gets", "put",
        "puts", "delete", "deletes");
  }

  @Override
  public void send(Message message) {
    URI address = URI.create(message.url());
    String scheme = address.getScheme().toLowerCase(Locale.ROOT);
    boolean secure = scheme.endsWith("s") && !scheme.equals("gets") ? scheme.endsWith("s") : scheme.endsWith("s");
    String targetScheme = scheme.endsWith("s") ? "https" : "http";

    Map<String, String> headers = new LinkedHashMap<>();
    Map<String, String> extraFields = new LinkedHashMap<>();
    for (String pair : query(address)) {
      int equals = pair.indexOf('=');
      if (equals < 0) {
        continue;
      }
      String key = decode(pair.substring(0, equals));
      String value = decode(pair.substring(equals + 1));
      if (key.startsWith("+")) {
        headers.put(key.substring(1), value);
      } else if (!key.equals("format") && !key.equals("avatar_url")) {
        extraFields.put(key, value);
      }
    }

    StringBuilder path = new StringBuilder();
    path.append(targetScheme).append("://");
    if (address.getRawUserInfo() != null) {
      path.append(address.getRawUserInfo()).append('@');
    }
    path.append(address.getHost());
    if (address.getPort() > 0) {
      path.append(':').append(address.getPort());
    }
    path.append(address.getRawPath() == null ? "" : address.getRawPath());

    String method =
        scheme.startsWith("get") ? "GET"
            : scheme.startsWith("put") ? "PUT"
                : scheme.startsWith("delete") ? "DELETE"
                    : "POST";

    Map<String, Object> payload = new LinkedHashMap<>(extraFields);
    payload.put("title", message.title() == null ? "" : message.title());
    payload.put("message", message.body() == null ? "" : message.body());
    payload.put("format", message.format());

    HttpRequest.Builder builder =
        HttpRequest.newBuilder().timeout(Duration.ofSeconds(30));
    for (Map.Entry<String, String> header : headers.entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }

    if (method.equals("GET") || method.equals("DELETE")) {
      StringBuilder query = new StringBuilder();
      for (Map.Entry<String, Object> field : payload.entrySet()) {
        query.append(query.length() == 0 ? '?' : '&');
        query.append(encode(field.getKey())).append('=').append(encode(String.valueOf(field.getValue())));
      }
      builder.uri(URI.create(path + query.toString()));
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else if (scheme.startsWith("form")) {
      StringBuilder form = new StringBuilder();
      for (Map.Entry<String, Object> field : payload.entrySet()) {
        if (form.length() > 0) {
          form.append('&');
        }
        form.append(encode(field.getKey())).append('=').append(encode(String.valueOf(field.getValue())));
      }
      builder.uri(URI.create(path.toString()));
      builder.header("Content-Type", "application/x-www-form-urlencoded");
      builder.method(method, HttpRequest.BodyPublishers.ofString(form.toString()));
    } else {
      String json;
      try {
        json = PythonJson.MAPPER.writeValueAsString(payload);
      } catch (Exception e) {
        throw new NotificationFailed("cannot write the notification payload: " + e.getMessage());
      }
      builder.uri(URI.create(path.toString()));
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }

    try {
      HttpResponse<String> response =
          CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new NotificationFailed(
            "The endpoint answered " + response.statusCode() + " to the notification");
      }
    } catch (NotificationFailed e) {
      throw e;
    } catch (Exception e) {
      throw new NotificationFailed(String.valueOf(e.getMessage()));
    }
  }

  private static List<String> query(URI address) {
    List<String> pairs = new ArrayList<>();
    String raw = address.getRawQuery();
    if (raw == null || raw.isEmpty()) {
      return pairs;
    }
    for (String pair : raw.split("&")) {
      pairs.add(pair);
    }
    return pairs;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String decode(String value) {
    return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
