package com.helpinminutes.api.helpers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HelperPayoutAccountRequest(
    @NotBlank @Size(max = 160) String accountHolderName,
    @Size(max = 160) String bankName,
    @NotBlank @Pattern(regexp = "\\d{4}", message = "Bank account last four digits are required") String bankAccountLast4,
    @NotBlank @Pattern(regexp = "^[A-Z]{4}0[A-Z0-9]{6}$", message = "Enter a valid IFSC code") String ifscCode,
    @Size(max = 160) String upiIdMasked
) {}
