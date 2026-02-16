package com.helpinminutes.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectHelperRequest(
    @NotBlank String reason
) {}
