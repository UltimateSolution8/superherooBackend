package com.helpinminutes.api.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;

public record KycStartRequest(
        @NotBlank String docType, // e.g. "AADHAAR", "DL", "PASSPORT"
        @AssertTrue(message = "Consent must be explicitly true") Boolean consent) {
}
