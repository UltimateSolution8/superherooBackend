package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.GeminiLlmClient;
import com.helpinminutes.api.moderation.llm.PromptBuilder;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Behaviour when the model providers cannot be reached.
 *
 * <p>This test previously asserted the opposite of what it does now, and that is the
 * point. It required the client to return {@code APPROVED, confidence 85, no flags}
 * when both providers were down — a silent fail-open. Because {@code evaluateTask}
 * never threw, the caller's fail-closed handler could not see the outage, and the
 * fabricated confidence sailed through the decision engine's auto-approve branch. A
 * provider outage auto-approved every ambiguous task on the platform.
 */
class GeminiLlmClientTest {

  private GeminiLlmClient client;

  @BeforeEach
  void setUp() {
    client = new GeminiLlmClient(new PromptBuilder(), new ObjectMapper());

    // Unreachable endpoints on both providers.
    ReflectionTestUtils.setField(client, "geminiApiKey", "invalid-gemini-key");
    ReflectionTestUtils.setField(client, "geminiEndpoint", "https://invalid.endpoint/gemini");
    ReflectionTestUtils.setField(client, "groqApiKey", "invalid-groq-key");
    ReflectionTestUtils.setField(client, "groqEndpoint", "https://invalid.endpoint/groq");
    ReflectionTestUtils.setField(client, "groqModel", "llama-3.1-8b-instant");
    ReflectionTestUtils.setField(client, "timeoutSeconds", 1);
  }

  @Test
  void returnsNoVerdictWhenEveryProviderIsUnreachable() {
    AIReviewResult result = client.evaluateTask(payload());

    // No verdict, rather than a fabricated approval. The decision engine turns this
    // into ADMIN_REVIEW for an escalated task; a locally-clean task never reaches
    // here at all, so the fast path is unaffected by a provider outage.
    assertNull(result, "an unreachable provider must not produce an approval");
  }

  @Test
  void doesNotCallAProviderThatHasNoKeyConfigured() {
    ReflectionTestUtils.setField(client, "geminiApiKey", "");
    ReflectionTestUtils.setField(client, "groqApiKey", "   ");

    long startedAt = System.currentTimeMillis();
    AIReviewResult result = client.evaluateTask(payload());
    long elapsed = System.currentTimeMillis() - startedAt;

    assertNull(result);
    // Unconfigured providers are skipped rather than dialled and timed out. The keys
    // used to fall back to values hardcoded in the client, so "no key" was never
    // actually reachable.
    assertTrue(elapsed < 500, "expected an immediate skip, took " + elapsed + "ms");
  }

  private static TaskModerationPayload payload() {
    return new TaskModerationPayload(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Clean my kitchen",
        "Wash dishes and organise shelves",
        "cleaning",
        50000L,
        "Hyderabad",
        Collections.emptyList());
  }
}
