package com.helpinminutes.api.admin.dto;

public record AdminSummaryResponse(
    long pendingHelpers,
    long searchingTasks,
    long assignedTasks,
    long arrivedTasks,
    long startedTasks,
    long completedTasks,
    long totalRevenuePaise
) {}
