package com.helpinminutes.api.admin.dto;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminSendNotificationRequest(
    @NotNull @Pattern(regexp = "(?i)ALL|CITIZEN|BUYER|PARTNER|HELPER|MEDIATOR") String role,
    @Size(max = 1000) List<UUID> userIds,
    @NotBlank @Size(max = 80) String title,
    @NotBlank @Size(max = 500) String body
) {}
