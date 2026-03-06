package com.helpinminutes.api.support.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.support.dto.AiDraftResponse;
import com.helpinminutes.api.support.model.SupportMessageEntity;
import com.helpinminutes.api.support.model.SupportTicketEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SupportAiService {
  private final ObjectMapper mapper;
  private final HttpClient http;

  public SupportAiService(ObjectMapper mapper) {
    this.mapper = mapper;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public AiDraftResponse draftReply(SupportTicketEntity ticket, List<SupportMessageEntity> messages) {
    String provider = env("LLM_PROVIDER");
    String apiKey = env("LLM_API_KEY");
    String model = env("LLM_MODEL");
    String baseUrl = env("LLM_BASE_URL");
    if (provider == null || provider.isBlank()) {
      if (apiKey != null && !apiKey.isBlank()) {
        provider = "openrouter";
      }
    }
    if (model == null || model.isBlank()) {
      model = "meta-llama/llama-3.1-8b-instruct:free";
    }

    if (apiKey == null || apiKey.isBlank()) {
      return AiDraftResponse.disabled("LLM_API_KEY not configured");
    }
    if (provider == null || provider.isBlank()) {
      return AiDraftResponse.disabled("LLM_PROVIDER not configured");
    }

    if (baseUrl == null || baseUrl.isBlank()) {
      if ("openai".equalsIgnoreCase(provider)) {
        baseUrl = "https://api.openai.com/v1/chat/completions";
      } else if ("openrouter".equalsIgnoreCase(provider)) {
        baseUrl = "https://openrouter.ai/api/v1/chat/completions";
      } else if ("deepseek".equalsIgnoreCase(provider)) {
        baseUrl = "https://api.deepseek.com/chat/completions";
      } else {
        return AiDraftResponse.disabled("Unsupported LLM_PROVIDER: " + provider);
      }
    }

    String prompt = buildPrompt(ticket, messages);
    try {
      JsonNode req = mapper.createObjectNode()
          .put("model", model)
          .put("temperature", 0.2)
          .set("messages", mapper.createArrayNode()
              .add(mapper.createObjectNode()
                  .put("role", "system")
                  .put("content",
                      "You are a support agent for Superheroo (a hyperlocal urgent micro-help marketplace). " +
                          "Write a helpful, concise reply. Do not ask for sensitive data (OTP, bank, Aadhaar). " +
                          "If safety-related, advise contacting local authorities and confirm escalation. " +
                          "Follow platform policy: no illegal tasks, harassment, adult services, weapons, drugs."))
              .add(mapper.createObjectNode()
                  .put("role", "user")
                  .put("content", prompt)));

      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl))
          .timeout(Duration.ofSeconds(25))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json");
      if ("openrouter".equalsIgnoreCase(provider)) {
        String appName = env("LLM_APP_NAME");
        String appUrl = env("LLM_APP_URL");
        if (appName != null && !appName.isBlank()) {
          builder.header("X-Title", appName);
        }
        if (appUrl != null && !appUrl.isBlank()) {
          builder.header("HTTP-Referer", appUrl);
        }
      }
      HttpRequest httpReq = builder
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
          .build();

      HttpResponse<String> res = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) {
        return AiDraftResponse.disabled("LLM request failed (status " + res.statusCode() + ")");
      }

      JsonNode root = mapper.readTree(res.body());
      String content = root.path("choices").path(0).path("message").path("content").asText(null);
      if (content == null || content.isBlank()) {
        return AiDraftResponse.disabled("LLM returned empty response");
      }
      return AiDraftResponse.enabled(content.trim());
    } catch (Exception e) {
      return AiDraftResponse.disabled("LLM call failed");
    }
  }

  private static String buildPrompt(SupportTicketEntity ticket, List<SupportMessageEntity> messages) {
    StringBuilder sb = new StringBuilder();
    sb.append("Ticket:").append("\n");
    sb.append("- Category: ").append(ticket.getCategory()).append("\n");
    sb.append("- Subject: ").append(ticket.getSubject() == null ? "" : ticket.getSubject()).append("\n");
    sb.append("- Status: ").append(ticket.getStatus()).append("\n");
    sb.append("- Priority: ").append(ticket.getPriority()).append("\n");
    sb.append("\nConversation:\n");

    int start = Math.max(0, messages.size() - 8);
    for (int i = start; i < messages.size(); i++) {
      SupportMessageEntity m = messages.get(i);
      sb.append(m.getAuthorType()).append(": ").append(m.getMessage()).append("\n");
    }

    sb.append("\nWrite the best next reply as SUPPORT (admin).");
    return sb.toString();
  }

  private static String env(String key) {
    try {
      return System.getenv(key);
    } catch (Exception e) {
      return null;
    }
  }
}
