package com.helpinminutes.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResetPasswordRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "\\d{4,8}", message = "Enter the code from your email") String otp,
    // Length and complexity are enforced in InputValidators.requirePassword so
    // the message matches the signup screen exactly.
    @NotBlank String newPassword
) {}
