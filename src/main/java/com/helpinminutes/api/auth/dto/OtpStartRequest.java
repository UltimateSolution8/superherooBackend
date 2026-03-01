package com.helpinminutes.api.auth.dto;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OtpStartRequest(
    @NotBlank @Pattern(regexp = "^\\d{10}$", message = "phone must be 10 digits") String phone,
    @NotNull UserRole role,
    String channel
) {}
