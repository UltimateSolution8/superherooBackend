package com.helpinminutes.api.notifications.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterPushTokenRequest(
    @NotBlank @Size(min = 20, max = 4096) String token,
    @NotBlank String platform
) {}
