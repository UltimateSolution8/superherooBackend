package com.helpinminutes.api.learn.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record HelperTrainingProgressRequest(
    @Min(0) @Max(100) Integer progressPercent,
    @Min(0) Integer viewedSeconds,
    Boolean completed
) {
}
