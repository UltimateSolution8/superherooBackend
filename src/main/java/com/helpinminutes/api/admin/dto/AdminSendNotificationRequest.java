package com.helpinminutes.api.admin.dto;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminSendNotificationRequest(
    @NotNull String role, // "ALL", "CITIZEN", "PARTNER"
    List<UUID> userIds,   // Optional specific user targets
    @NotBlank String title,
    @NotBlank String body
) {}
