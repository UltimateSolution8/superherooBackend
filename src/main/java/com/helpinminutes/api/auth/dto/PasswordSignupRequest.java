package com.helpinminutes.api.auth.dto;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PasswordSignupRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    // Mandatory at launch: partners are dispatched to a citizen's location and
    // both sides need a reachable number if something goes wrong on site.
    @NotBlank(message = "Phone number is required")
    @Pattern(
        regexp = "^([6-9]\\d{9}|91[6-9]\\d{9}|0[6-9]\\d{9})$",
        message = "Enter a valid 10-digit Indian mobile number")
    String phone,
    @NotBlank(message = "Name is required") String displayName,
    @NotNull UserRole role
) {}
