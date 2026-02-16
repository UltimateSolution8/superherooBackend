package com.helpinminutes.api.admin.dto;

import jakarta.validation.constraints.Email;

public record AdminUpdateUserRequest(
    String phone,
    @Email String email,
    String displayName,
    String status
) {}
