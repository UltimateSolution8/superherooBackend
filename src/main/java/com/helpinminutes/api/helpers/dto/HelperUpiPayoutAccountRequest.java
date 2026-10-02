package com.helpinminutes.api.helpers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HelperUpiPayoutAccountRequest(
    @NotBlank @Size(min = 3, max = 160) String accountHolderName,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$", message = "Enter a valid UPI ID (e.g. name@upi)") String upiId,
    @NotBlank @Size(max = 160) String changeToken
) {}
