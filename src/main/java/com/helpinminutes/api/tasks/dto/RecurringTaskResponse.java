package com.helpinminutes.api.tasks.dto;

import com.helpinminutes.api.tasks.model.RecurringTaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringTaskResponse(
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
    String frequency,
    LocalDate startDate,
    LocalDate endDate,
    String timeSlot,
    Instant createdAt,
    RecurringTaskStatus status,
    Integer recurrenceInterval,
    int[] byDay,
    Integer byMonthDay,
    String timezone
) {}
