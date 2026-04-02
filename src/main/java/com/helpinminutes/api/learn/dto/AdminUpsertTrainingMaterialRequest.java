package com.helpinminutes.api.learn.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUpsertTrainingMaterialRequest(
    @NotBlank @Size(max = 180) String title,
    @Size(max = 5000) String description,
    @NotNull @Size(max = 24) String contentType,
    @NotBlank @Size(max = 4000) String resourceUrl,
    @Size(max = 4000) String thumbnailUrl,
    @Min(0) @Max(86400) Integer durationSeconds,
    Boolean active
) {
}
