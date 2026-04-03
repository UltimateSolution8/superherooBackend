package com.helpinminutes.api.learn.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record HelperAssessmentSubmitRequest(
    @NotNull UUID attemptId,
    @NotNull JsonNode answers
) {
}
