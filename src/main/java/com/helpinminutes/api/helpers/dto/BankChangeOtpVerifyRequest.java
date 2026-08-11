package com.helpinminutes.api.helpers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record BankChangeOtpVerifyRequest(
    @NotNull UUID challengeId,
    @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Enter the 6-digit verification code") String otp
) {}

