package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.PromptBuilder;
import com.helpinminutes.api.moderation.llm.ZenMuxLlmClient;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZenMuxLlmClientTest {

  private ZenMuxLlmClient client;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    PromptBuilder promptBuilder = new PromptBuilder();
    client = new ZenMuxLlmClient(promptBuilder, objectMapper);
    client.setApiKey("test-invalid-key");
    client.setPrimaryModel("invalid-primary-model");
    client.setFallbackModel("invalid-fallback-model");
    client.setTimeoutSeconds(1);
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
    assertTrue(result.flags().isEmpty());
    assertEquals("APPROVED", result.status());
    assertFalse(result.requiresAdminReview());
  }
}
