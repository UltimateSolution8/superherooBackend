package com.helpinminutes.api.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared JSON transport for the geo providers.
 *
 * <p>Uses the JDK client, matching {@code GeminiLlmClient} — no new dependency.
 * Swallows every failure into {@code Optional.empty()} so a provider can honour
 * the never-throw contract in {@link GeoProvider} without repeating try/catch.
 *
 * <p>Two things here exist purely because of Ola. It enforces its credential
 * allowlist against the {@code Origin} header rather than the network source, so
 * a server-to-server call that sends none is rejected with
 * {@code 403 "Domain  is not allowed."} — hence {@link #getJson(String, int,
 * String, Map)}. And its directions endpoint answers only to POST, returning a
 * bare {@code 404 Route Not Found} to a GET of the same URL, which reads exactly
 * like a routing failure rather than a wrong method — hence {@link #postJson}.
 */
@Component
public class GeoHttp {

  private static final Logger log = LoggerFactory.getLogger(GeoHttp.class);

  private final HttpClient client;
  private final ObjectMapper objectMapper;

  public GeoHttp(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * GETs {@code url} and parses the body as JSON.
   *
   * @param providerName only for logging; never include the URL in a log line,
   *     since it carries the API key as a query parameter
   */
  public Optional<JsonNode> getJson(String url, int timeoutMs, String providerName) {
    return getJson(url, timeoutMs, providerName, Map.of());
  }

  /** As {@link #getJson(String, int, String)}, with extra request headers. */
  public Optional<JsonNode> getJson(
      String url, int timeoutMs, String providerName, Map<String, String> headers) {
    return send("GET", url, timeoutMs, providerName, headers);
  }

  /**
   * POSTs {@code url} with an empty body and parses the response as JSON.
   *
   * <p>For upstreams that take their parameters in the query string but refuse
   * GET. Nothing here sends a request body.
   */
  public Optional<JsonNode> postJson(
      String url, int timeoutMs, String providerName, Map<String, String> headers) {
    return send("POST", url, timeoutMs, providerName, headers);
  }

  private Optional<JsonNode> send(
      String method, String url, int timeoutMs, String providerName, Map<String, String> headers) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofMillis(Math.max(200, timeoutMs)))
          .header("Accept", "application/json")
          .header("User-Agent", "Superherooo/1.0")
          .method(method, HttpRequest.BodyPublishers.noBody());
      if (headers != null) {
        headers.forEach((name, value) -> {
          if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
            builder.header(name, value);
          }
        });
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 429) {
        log.warn("Geo provider {} rate-limited (429)", providerName);
        return Optional.empty();
      }
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        // Called out separately because a rejected credential looks identical to
        // "no results" once the chain has fallen through: the app shows an empty
        // dropdown either way. This is the line that says the key is the problem.
        log.warn(
            "Geo provider {} rejected our credentials (HTTP {}). Check the API key and, for"
                + " providers with an origin/domain allowlist, that the configured origin is"
                + " listed.",
            providerName,
            response.statusCode());
        return Optional.empty();
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.warn("Geo provider {} returned HTTP {}", providerName, response.statusCode());
        return Optional.empty();
      }
      return Optional.of(objectMapper.readTree(response.body()));
    } catch (java.io.InterruptedIOException | java.net.http.HttpTimeoutException e) {
      log.warn("Geo provider {} timed out after {}ms", providerName, timeoutMs);
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      log.warn("Geo provider {} call failed: {}", providerName, e.getMessage());
      return Optional.empty();
    }
  }

  public static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  /** Null-safe double read; returns null rather than 0 for a missing node. */
  public static Double asDouble(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() || !node.isNumber()
        ? null
        : node.asDouble();
  }

  /** Null-safe text read; blank and missing both become null. */
  public static String asText(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    String text = node.asText();
    return text == null || text.isBlank() ? null : text;
  }
}
