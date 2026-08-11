package com.helpinminutes.api.helpers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import com.helpinminutes.api.helpers.dto.IfscLookupResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IfscLookupService {
  private static final Duration CACHE_TTL = Duration.ofHours(24);
  private record Cached(IfscLookupResponse value, Instant expiresAt) {}

  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final Duration requestTimeout;
  private final HttpClient http;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  @Autowired
  public IfscLookupService(
      ObjectMapper objectMapper,
      @Value("${banking.ifsc-base-url:https://ifsc.razorpay.com}") String baseUrl,
      @Value("${banking.ifsc-connect-timeout-ms:1500}") long connectTimeoutMs,
      @Value("${banking.ifsc-request-timeout-ms:3000}") long requestTimeoutMs) {
    this(objectMapper, baseUrl, Duration.ofMillis(requestTimeoutMs),
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
  }

  IfscLookupService(ObjectMapper objectMapper, String baseUrl, Duration requestTimeout, HttpClient http) {
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.requestTimeout = requestTimeout;
    this.http = http;
  }

  public IfscLookupResponse lookup(String rawCode) {
    String code = normalize(rawCode);
    Cached cached = cache.get(code);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.value();
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/" + code))
          .timeout(requestTimeout)
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) throw new BadRequestException("IFSC code was not found");
      if (response.statusCode() != 200) {
        // The public service documents 404 as the only authoritative "not
        // found" response. Authentication, throttling and upstream failures
        // must never be presented to a partner as an invalid branch code.
        throw new ServiceUnavailableException("IFSC verification is temporarily unavailable");
      }
      JsonNode root = objectMapper.readTree(response.body());
      String returnedIfsc = text(root, "IFSC");
      String bank = text(root, "BANK");
      if (!code.equalsIgnoreCase(returnedIfsc) || bank == null) {
        throw new ServiceUnavailableException("IFSC verification returned an invalid response");
      }
      IfscLookupResponse result = new IfscLookupResponse(
          code, bank, text(root, "BRANCH"), text(root, "CITY"), text(root, "DISTRICT"), text(root, "STATE"));
      if (cache.size() > 10_000) cache.clear();
      cache.put(code, new Cached(result, Instant.now().plus(CACHE_TTL)));
      return result;
    } catch (BadRequestException | ServiceUnavailableException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("IFSC verification was interrupted");
    } catch (Exception e) {
      throw new ServiceUnavailableException("IFSC verification is temporarily unavailable");
    }
  }

  public static String normalize(String rawCode) {
    String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    if (!code.matches("^[A-Z]{4}0[A-Z0-9]{6}$")) {
      throw new BadRequestException("Enter a valid 11-character IFSC code");
    }
    return code;
  }

  private static String text(JsonNode root, String field) {
    String value = root.path(field).asText(null);
    return value == null || value.isBlank() ? null : value.trim();
  }
}
