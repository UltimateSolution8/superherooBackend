package com.helpinminutes.api.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddMessageRequest(
    @NotBlank @Size(max = 4000) String message
) {}

