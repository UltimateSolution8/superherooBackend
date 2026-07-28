package com.helpinminutes.api.moderation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ZenMuxLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(ZenMuxLlmClient.class);

  @Value("${ai.moderation.zenmux.api-key:}")
  private String apiKey;

  @Value("${ai.moderation.zenmux.endpoint:https://zenmux.ai/api/v1/chat/completions}")
  private String endpoint;

  @Value("${ai.moderation.zenmux.primary-model:moonshotai/kimi-k3-free}")
  private String primaryModel;

  @Value("${ai.moderation.zenmux.fallback-model:z-ai/glm-4.7-flash-free}")
  private String fallbackModel;

  @Value("${ai.moderation.zenmux.timeout-seconds:10}")
  private int timeoutSeconds;

  private final PromptBuilder promptBuilder;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public ZenMuxLlmClient(PromptBuilder promptBuilder, ObjectMapper objectMapper) {
    this.promptBuilder = promptBuilder;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @Override
  public AIReviewResult evaluateTask(TaskModerationPayload payload) {
    long startTime = System.currentTimeMillis();
    String systemPrompt = promptBuilder.buildSystemPrompt();
    String userPrompt = promptBuilder.buildUserPrompt(payload);

    if (apiKey == null || apiKey.isBlank()) {
      log.warn("AI moderation API key is not configured; approving task {} using regex fallback", payload.taskId());
      return regexFallbackApproval(startTime, "AI moderation key not configured; approved by local regex fallback");
    }

    // Try Primary Model
    try {
      log.info("Evaluating task {} using primary model {}", payload.taskId(), primaryModel);
      AIReviewResult result = callZenMux(primaryModel, systemPrompt, userPrompt, startTime);
      if (result != null) {
        return result;
      }
    } catch (Exception e) {
      log.warn("Primary model {} failed for task {}: {}. Triggering fallback model {}", 
          primaryModel, payload.taskId(), e.getMessage(), fallbackModel);
    }

    // Try Fallback Model
    try {
      log.info("Evaluating task {} using fallback model {}", payload.taskId(), fallbackModel);
      AIReviewResult fallbackResult = callZenMux(fallbackModel, systemPrompt, userPrompt, startTime);
      if (fallbackResult != null) {
        return fallbackResult;
      }
    } catch (Exception e) {
      log.error("Fallback model {} failed for task {}: {}", fallbackModel, payload.taskId(), e.getMessage());
    }

    // Launch-safe fallback if both models fail or timeout. Restricted work is
    // already caught by TaskModerationService before this client is called.
    return regexFallbackApproval(startTime, "LLM unavailable; approved by local regex fallback");
  }

  private AIReviewResult regexFallbackApproval(long startTime, String reason) {
    long duration = System.currentTimeMillis() - startTime;
    return new AIReviewResult(
        "APPROVED",
        82,
        10,
        75,
        List.of(reason),
        List.of(),
        false,
        "{\"fallback\":\"regex\"}",
        "fallback-rule",
        duration
    );
  }

  private AIReviewResult callZenMux(String model, String systemPrompt, String userPrompt, long startTime) throws Exception {
    Map<String, Object> requestBody = Map.of(
        "model", model,
        "temperature", 0.1,
        "max_tokens", 700,
        "messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        )
    );

    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("ZenMux API error status: " + response.statusCode() + ", body: " + response.body());
    }

    JsonNode root = objectMapper.readTree(response.body());
    JsonNode choices = root.path("choices");
    if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
      throw new RuntimeException("Invalid response format from ZenMux API");
    }

    String content = choices.get(0).path("message").path("content").asText();
    long duration = System.currentTimeMillis() - startTime;

    return parseAiJson(content, response.body(), model, duration);
  }

  private AIReviewResult parseAiJson(String content, String rawResponse, String model, long duration) {
    try {
      // Clean up markdown block markers if present
      String cleaned = content.trim();
      if (cleaned.startsWith("```json")) {
        cleaned = cleaned.substring(7);
      } else if (cleaned.startsWith("```")) {
        cleaned = cleaned.substring(3);
      }
      if (cleaned.endsWith("```")) {
        cleaned = cleaned.substring(0, cleaned.length() - 3);
      }
      cleaned = cleaned.trim();

      JsonNode node = objectMapper.readTree(cleaned);

      String status = node.path("status").asText("REVIEW").toUpperCase();
      int confidence = node.path("confidence").asInt(80);
      int riskScore = node.path("riskScore").asInt(20);
      int qualityScore = node.path("qualityScore").asInt(80);
      boolean requiresAdminReview = node.path("requiresAdminReview").asBoolean(!"APPROVED".equals(status));

      List<String> reasons = new ArrayList<>();
      if (node.has("reasons") && node.path("reasons").isArray()) {
        node.path("reasons").forEach(r -> reasons.add(r.asText()));
      }

      List<String> flags = new ArrayList<>();
      if (node.has("flags") && node.path("flags").isArray()) {
        node.path("flags").forEach(f -> flags.add(f.asText()));
      }

      return new AIReviewResult(
          status,
          confidence,
          riskScore,
          qualityScore,
          reasons,
          flags,
          requiresAdminReview,
          rawResponse,
          model,
          duration
      );
    } catch (Exception e) {
      log.error("Failed to parse AI JSON content: {}. Error: {}", content, e.getMessage());
      return regexFallbackApproval(System.currentTimeMillis() - duration, "Unparseable AI response; approved by local regex fallback");
    }
  }

  // Setters for unit testing
  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public void setPrimaryModel(String primaryModel) {
    this.primaryModel = primaryModel;
  }

  public void setFallbackModel(String fallbackModel) {
    this.fallbackModel = fallbackModel;
  }

  public void setTimeoutSeconds(int timeoutSeconds) {
    this.timeoutSeconds = timeoutSeconds;
  }
}
