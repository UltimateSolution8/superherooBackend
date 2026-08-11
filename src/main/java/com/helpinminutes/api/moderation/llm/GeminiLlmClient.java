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
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class GeminiLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

  @Value("${AI_MODERATION_GEMINI_API_KEY:}")
  private String geminiApiKey;

  // Flash-Lite is the cheapest current model ($0.10/$0.40 per million tokens) and
  // this is a short classification task, not a reasoning one. At ~800 in / ~80 out
  // that is roughly a paisa per escalated task.
  //
  // Deliberately the PAID tier: the free tier caps at 1,000 requests a day and its
  // content may be used to improve the provider's products, which is wrong for text
  // citizens wrote about their homes.
  @Value("${ai.moderation.gemini.endpoint:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent}")
  private String geminiEndpoint;

  @Value("${AI_MODERATION_GROQ_API_KEY:}")
  private String groqApiKey;

  @Value("${ai.moderation.groq.endpoint:https://api.groq.com/openai/v1/chat/completions}")
  private String groqEndpoint;

  @Value("${ai.moderation.groq.model:llama-3.1-8b-instant}")
  private String groqModel;

  // 8s was too long to sit on a request thread; the cascade means only the
  // ambiguous minority waits at all, but that minority still should not wait long.
  @Value("${ai.moderation.timeout-seconds:4}")
  private int timeoutSeconds;

  private final PromptBuilder promptBuilder;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public GeminiLlmClient(PromptBuilder promptBuilder, ObjectMapper objectMapper) {
    this.promptBuilder = promptBuilder;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build();
  }

  @Override
  public AIReviewResult evaluateTask(TaskModerationPayload payload) {
    long startTime = System.currentTimeMillis();
    String systemPrompt = promptBuilder.buildSystemPrompt();
    String userPrompt = promptBuilder.buildUserPrompt(payload);

    // 1. Try Gemini
    if (geminiConfigured()) {
      try {
        AIReviewResult result = callGemini(systemPrompt, userPrompt, startTime);
        if (result != null) {
          return result;
        }
      } catch (Exception e) {
        log.warn("Gemini failed for task {}: {}. Trying Groq...", payload.taskId(), e.getMessage());
      }
    }

    // 2. Try Groq fallback
    if (groqConfigured()) {
      try {
        AIReviewResult fallbackResult = callGroq(systemPrompt, userPrompt, startTime);
        if (fallbackResult != null) {
          return fallbackResult;
        }
      } catch (Exception e) {
        log.error("Groq fallback failed for task {}: {}", payload.taskId(), e.getMessage());
      }
    }

    // 3. Both providers unreachable.
    //
    // This used to fabricate APPROVED with confidence 85 and riskScore 10, which
    // sailed straight through the decision engine's auto-approve branch. So a
    // provider outage silently auto-approved every ambiguous task — and because
    // evaluateTask never threw, the enclosing fail-closed handler could not see it.
    //
    // Returning null instead says "no verdict", and the decision engine routes an
    // escalated task with no verdict to a human. Only text that local screening
    // already found clean is approved without a model, and that decision is made
    // before we ever get here.
    long duration = System.currentTimeMillis() - startTime;
    log.error("No moderation verdict available for task {} after {}ms — routing to review",
        payload.taskId(), duration);
    return null;
  }

  private AIReviewResult callGemini(String systemPrompt, String userPrompt, long startTime) throws Exception {
    String combinedPrompt = "System Instruction:\n" + systemPrompt + "\n\nTask details to review:\n" + userPrompt;

    Map<String, Object> contents = Map.of(
        "parts", List.of(Map.of("text", combinedPrompt))
    );
    // temperature 0 for a classification task — there is nothing creative to do here,
    // and a deterministic verdict is what makes the result cache meaningful.
    //
    // maxOutputTokens was previously unset on this path, so the response length (and
    // the bill) was unbounded. 384 is comfortably more than the JSON schema needs.
    Map<String, Object> generationConfig = Map.of(
        "responseMimeType", "application/json",
        "temperature", 0,
        "maxOutputTokens", 384
    );
    Map<String, Object> requestBody = Map.of(
        "contents", List.of(contents),
        "generationConfig", generationConfig
    );

    String jsonBody = objectMapper.writeValueAsString(requestBody);
    URI targetUri = URI.create(geminiEndpoint + "?key=" + getGeminiApiKey());

    HttpRequest request = HttpRequest.newBuilder()
        .uri(targetUri)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("Gemini API returned status " + response.statusCode() + ": " + response.body());
    }

    JsonNode root = objectMapper.readTree(response.body());
    JsonNode textNode = root.path("candidates").get(0).path("content").path("parts").get(0).path("text");
    if (textNode.isMissingNode()) {
      throw new RuntimeException("Invalid response format from Gemini API: missing candidates/content/parts/text");
    }

    String content = textNode.asText();
    long duration = System.currentTimeMillis() - startTime;

    return parseAiJson(content, response.body(), geminiModelName(), duration);
  }

  private AIReviewResult callGroq(String systemPrompt, String userPrompt, long startTime) throws Exception {
    Map<String, Object> requestBody = Map.of(
        "model", groqModel,
        "temperature", 0.1,
        "max_tokens", 700,
        "messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ),
        "response_format", Map.of("type", "json_object")
    );

    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(groqEndpoint))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + getGroqApiKey())
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("Groq API returned status " + response.statusCode() + ": " + response.body());
    }

    JsonNode root = objectMapper.readTree(response.body());
    JsonNode choice = root.path("choices").get(0);
    if (choice.isMissingNode()) {
      throw new RuntimeException("Invalid response format from Groq API");
    }

    String content = choice.path("message").path("content").asText();
    long duration = System.currentTimeMillis() - startTime;

    return parseAiJson(content, response.body(), groqModel, duration);
  }

  private AIReviewResult parseAiJson(String content, String rawResponse, String model, long duration) {
    try {
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

      // Normalised to APPROVED / REVIEW / BLOCK. Models write "APPROVE" and
      // "APPROVED" interchangeably, and the downstream comparison is exact — an
      // unnormalised "APPROVE" would default requiresAdminReview to true and quietly
      // route every approved task to a human.
      String status = normalizeStatus(node.path("status").asText("REVIEW"));
      int confidence = node.path("confidence").asInt(80);
      int riskScore = node.path("riskScore").asInt(20);
      int qualityScore = node.path("qualityScore").asInt(80);
      boolean requiresAdminReview =
          node.path("requiresAdminReview").asBoolean("REVIEW".equals(status));

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
      // Fail closed. An unreadable verdict is not an approval.
      return new AIReviewResult(
          "REVIEW",
          60,
          70,
          40,
          List.of("Unparseable AI response; sending to admin review for safety"),
          List.of("UNPARSEABLE_AI_OUTPUT"),
          true,
          rawResponse,
          model,
          duration
      );
    }
  }

  /**
   * Canonicalises the model's status word.
   *
   * <p>Anything unrecognised becomes REVIEW: an unexpected value means we do not know
   * what the model decided, and that is not grounds for approval.
   */
  private static String normalizeStatus(String raw) {
    String upper = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    return switch (upper) {
      case "APPROVE", "APPROVED", "OK", "ALLOW" -> "APPROVED";
      case "BLOCK", "BLOCKED", "REJECT", "REJECTED", "DENY" -> "BLOCK";
      default -> "REVIEW";
    };
  }

  /**
   * The configured key, or blank when the provider is not set up.
   *
   * <p>Both keys used to be embedded here as split string literals — an obvious
   * attempt to get past secret scanning — and were used whenever the environment
   * variable was empty, which means production was very likely running on them. Both
   * must be treated as compromised and rotated. A missing key now disables the
   * provider rather than silently falling back to a shared one; see
   * {@code coding-standards.md}: "A missing secret should fail startup, not fall back
   * to something."
   */
  private String getGeminiApiKey() {
    return geminiApiKey == null ? "" : geminiApiKey.trim();
  }

  private String getGroqApiKey() {
    return groqApiKey == null ? "" : groqApiKey.trim();
  }

  /** Model name for the audit row, derived from the configured endpoint. */
  private String geminiModelName() {
    int start = geminiEndpoint.lastIndexOf("/models/");
    int end = geminiEndpoint.lastIndexOf(":generateContent");
    if (start < 0 || end < 0 || end <= start) return "gemini";
    return geminiEndpoint.substring(start + "/models/".length(), end);
  }

  private boolean geminiConfigured() {
    return !getGeminiApiKey().isBlank();
  }

  private boolean groqConfigured() {
    return !getGroqApiKey().isBlank();
  }
}
