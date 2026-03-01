package com.helpinminutes.api.notifications.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterPushTokenRequest(
    @NotBlank String token,
    @NotBlank String platform
) {}
