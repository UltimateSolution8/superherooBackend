package com.helpinminutes.api.auth.dto;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OtpVerifyRequest(
    @NotBlank String phone,
    @NotBlank String otp,
    @NotNull UserRole role
) {}
