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
    if (model == null || model.isBlank()) model = "gpt-4o-mini";

    if (provider == null || provider.isBlank()) {
      return AiDraftResponse.disabled("LLM_PROVIDER not configured");
    }
    if (apiKey == null || apiKey.isBlank()) {
      return AiDraftResponse.disabled("LLM_API_KEY not configured");
    }

    if (!"openai".equalsIgnoreCase(provider)) {
      return AiDraftResponse.disabled("Unsupported LLM_PROVIDER: " + provider);
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
                      "You are a support agent for HelpInMinutes (a hyperlocal urgent micro-help marketplace). " +
                          "Write a helpful, concise reply. Do not ask for sensitive data. " +
                          "If safety-related, advise reporting and offer escalation."))
              .add(mapper.createObjectNode()
                  .put("role", "user")
                  .put("content", prompt)));

      HttpRequest httpReq = HttpRequest.newBuilder()
          .uri(URI.create("https://api.openai.com/v1/chat/completions"))
          .timeout(Duration.ofSeconds(25))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json")
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

