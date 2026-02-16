package com.helpinminutes.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record HelperKycSignupRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    String phone,
    String displayName,
    @NotBlank String fullName,
    @NotBlank String idNumber
) {}
