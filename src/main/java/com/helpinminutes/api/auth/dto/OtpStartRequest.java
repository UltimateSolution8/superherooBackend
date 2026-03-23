package com.helpinminutes.api.auth.dto;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OtpStartRequest(
    @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "phone must be a valid Indian mobile number") String phone,
    @NotNull UserRole role,
    String channel
) {}
