package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.PromptBuilder;
import com.helpinminutes.api.moderation.llm.GeminiLlmClient;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GeminiLlmClientTest {

  private GeminiLlmClient client;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    PromptBuilder promptBuilder = new PromptBuilder();
    client = new GeminiLlmClient(promptBuilder, objectMapper);
    
    // Set invalid configs to trigger fallback and final failure handling
    ReflectionTestUtils.setField(client, "geminiApiKey", "invalid-gemini-key");
    ReflectionTestUtils.setField(client, "geminiEndpoint", "https://invalid.endpoint/gemini");
    ReflectionTestUtils.setField(client, "groqApiKey", "invalid-groq-key");
    ReflectionTestUtils.setField(client, "groqEndpoint", "https://invalid.endpoint/groq");
    ReflectionTestUtils.setField(client, "groqModel", "llama3-8b-8192");
    ReflectionTestUtils.setField(client, "timeoutSeconds", 1);
  }

  @Test
  void handlesLlmFailureGracefullyWithSafeFallback() {
    TaskModerationPayload payload = new TaskModerationPayload(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Clean my kitchen",
        "Wash dishes and organize shelves",
        "Cleaning",
        50000L,
        "Hyderabad",
        Collections.emptyList()
    );

    AIReviewResult result = client.evaluateTask(payload);
    assertNotNull(result);
    assertEquals("REVIEW", result.status());
    assertTrue(result.requiresAdminReview());
    assertTrue(result.flags().contains("ALL_LLM_FAILED"));
  }
}
