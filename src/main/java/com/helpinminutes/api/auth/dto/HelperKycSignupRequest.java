package com.helpinminutes.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record HelperKycSignupRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    @Pattern(regexp = "^[6-9]\\d{9}$", message = "phone must be a valid Indian mobile number") String phone,
    String displayName,
    @NotBlank String fullName,
    @NotBlank String idNumber
) {}
