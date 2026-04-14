package com.helpinminutes.api.learn.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record AdminAssessmentAssignmentRequest(
    @NotNull Boolean assignAll,
    List<UUID> helperIds
) {}
