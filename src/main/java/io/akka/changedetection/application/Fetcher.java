package io.akka.changedetection.application;

import io.akka.changedetection.domain.ContentType;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Fetches a page. The only part of the port that talks to the network. */
public class Fetcher {

  public record Fetched(String body, ContentType contentType, int statusCode) {}

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  public Fetched fetch(String url) {
    var request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "changedetection-io-akka")
            .GET()
            .build();
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return new Fetched(
          response.body(),
          ContentType.fromHeader(response.headers().firstValue("content-type").orElse(null)),
          response.statusCode());
    } catch (IOException e) {
      throw new IllegalStateException("fetch failed for " + url, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fetch interrupted for " + url, e);
    }
  }
}
