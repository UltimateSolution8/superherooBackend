package com.helpinminutes.api.auth.dto;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OtpVerifyRequest(
    @NotBlank @Pattern(regexp = "^\\d{10}$", message = "phone must be 10 digits") String phone,
    @NotBlank String otp,
    @NotNull UserRole role
) {}
