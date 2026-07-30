package com.helpinminutes.api.users.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MojoAuthClient {
  private static final URI SEND_URI = URI.create("https://api.mojoauth.com/users/emailotp");
  private static final URI VERIFY_URI = URI.create("https://api.mojoauth.com/users/emailotp/verify");

  private final ObjectMapper mapper;
  private final HttpClient http;
  private final String apiKey;

  public MojoAuthClient(
      ObjectMapper mapper,
      @Value("${mojoauth.api-key:}") String apiKey) {
    this.mapper = mapper;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
  }

  public boolean isConfigured() {
    return !apiKey.isBlank();
  }

  public String sendEmailOtp(String email) throws Exception {
    JsonNode body = post(SEND_URI, Map.of("email", email));
    return body.path("state_id").asText("");
  }

  public boolean verifyEmailOtp(String stateId, String otp) throws Exception {
    JsonNode body = post(VERIFY_URI, Map.of("state_id", stateId, "otp", otp));
    // Current MojoAuth responses include authenticated=true. Preserve
    // compatibility with older successful responses where this field is absent.
    return !body.has("authenticated") || body.path("authenticated").asBoolean(false);
  }

  private JsonNode post(URI uri, Map<String, String> payload) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(8))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("X-API-Key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
        .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("MojoAuth returned HTTP " + response.statusCode());
    }
    return mapper.readTree(response.body());
  }
}
