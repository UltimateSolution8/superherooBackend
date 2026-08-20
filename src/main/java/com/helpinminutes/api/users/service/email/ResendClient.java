package com.helpinminutes.api.users.service.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ResendClient {
  private static final Logger log = LoggerFactory.getLogger(ResendClient.class);
  private static final URI RESEND_EMAILS_URI = URI.create("https://api.resend.com/emails");

  private final ObjectMapper mapper;
  private final HttpClient http;
  private final String apiKey;
  private final String fromEmail;

  public ResendClient(
      ObjectMapper mapper,
      @Value("${resend.api-key:}") String apiKey,
      @Value("${resend.from-email:Superherooo <onboarding@resend.dev>}") String fromEmail) {
    this.mapper = mapper;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.fromEmail = fromEmail == null || fromEmail.isBlank() ? "Superherooo <onboarding@resend.dev>" : fromEmail.trim();
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public boolean isConfigured() {
    return !apiKey.isBlank();
  }

  public boolean sendEmail(String toEmail, String subject, String htmlContent) {
    if (!isConfigured()) {
      log.warn("Resend API key is not configured; skipping email delivery to {}", toEmail);
      return false;
    }

    try {
      Map<String, Object> payload = Map.of(
          "from", fromEmail,
          "to", List.of(toEmail),
          "subject", subject,
          "html", htmlContent
      );

      HttpRequest request = HttpRequest.newBuilder(RESEND_EMAILS_URI)
          .timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
          .build();

      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        JsonNode body = mapper.readTree(response.body());
        String emailId = body.path("id").asText("");
        log.info("Email delivered via Resend API to {} with id: {}", toEmail, emailId);
        return true;
      } else {
        log.error("Resend API error (HTTP {}): {}", response.statusCode(), response.body());
        return false;
      }
    } catch (Exception e) {
      log.error("Failed to send email via Resend API to {}: {}", toEmail, e.getMessage(), e);
      return false;
    }
  }
}
