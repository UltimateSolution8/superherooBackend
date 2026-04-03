package com.helpinminutes.api.learn.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUpsertAssessmentRequest(
    @NotBlank @Size(max = 180) String title,
    @Size(max = 5000) String description,
    @Size(max = 10000) String instructions,
    @NotNull @Min(1) @Max(20) Integer maxAttempts,
    @Min(1) @Max(240) Integer timeLimitMinutes,
    @NotNull @Min(0) @Max(100) Integer passPercentage,
    @NotNull JsonNode questionSchema,
    Boolean active
) {
}
