package com.helpinminutes.api.moderation.llm;

import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;

public interface LlmClient {
  AIReviewResult evaluateTask(TaskModerationPayload payload);
}
