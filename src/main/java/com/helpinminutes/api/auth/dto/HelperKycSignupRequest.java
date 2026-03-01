package com.helpinminutes.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record HelperKycSignupRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    @Pattern(regexp = "^\\d{10}$", message = "phone must be 10 digits") String phone,
    String displayName,
    @NotBlank String fullName,
    @NotBlank String idNumber
) {}
