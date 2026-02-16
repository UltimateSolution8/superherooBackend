package com.helpinminutes.api.tasks.dto;

import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
    UUID id,
    UUID buyerId,
    String title,
    String description,
    TaskUrgency urgency,
    Integer timeMinutes,
    Long budgetPaise,
    double lat,
    double lng,
    String addressText,
    TaskStatus status,
    UUID assignedHelperId,
    String arrivalSelfieUrl,
    Double arrivalSelfieLat,
    Double arrivalSelfieLng,
    String arrivalSelfieAddress,
    Instant arrivalSelfieCapturedAt,
    String completionSelfieUrl,
    Double completionSelfieLat,
    Double completionSelfieLng,
    String completionSelfieAddress,
    Instant completionSelfieCapturedAt,
    Instant createdAt
) {}
