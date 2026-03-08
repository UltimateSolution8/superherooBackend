package com.helpinminutes.api.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KycActionRequest(
        @NotBlank String action, // APPROVE, REJECT
        @NotBlank String remarks) {
}
