package com.helpinminutes.api.admin.dto;

import jakarta.validation.constraints.Email;

public record AdminCreateUserRequest(
    String phone,
    @Email String email,
    String displayName,
    String password,
    String status
) {}
