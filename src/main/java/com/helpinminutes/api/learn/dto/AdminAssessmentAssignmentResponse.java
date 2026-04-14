package com.helpinminutes.api.learn.dto;

import java.util.List;
import java.util.UUID;

public record AdminAssessmentAssignmentResponse(
    UUID assessmentId,
    boolean assignAll,
    int assignedCount,
    List<UUID> helperIds
) {}
