package com.helpinminutes.api.auth.dto;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PasswordSignupRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    String phone,
    String displayName,
    @NotNull UserRole role
) {}
