package com.helpinminutes.api.helpers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PayoutAccountUpdateRequest(
    @NotBlank @Size(min = 3, max = 160) String accountHolderName,
    @NotBlank @Size(max = 40)
    @Pattern(regexp = "^[0-9 ]+$", message = "Enter a valid bank account number") String bankAccountNumber,
    @NotBlank @Pattern(regexp = "(?i)^[A-Z]{4}0[A-Z0-9]{6}$", message = "Enter a valid IFSC code") String ifscCode,
    @NotBlank @Size(max = 160) String changeToken
) {
  public HelperPayoutAccountRequest details() {
    return new HelperPayoutAccountRequest(accountHolderName, bankAccountNumber, ifscCode);
  }
}

