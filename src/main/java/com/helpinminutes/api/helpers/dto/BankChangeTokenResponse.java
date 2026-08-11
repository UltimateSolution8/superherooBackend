package com.helpinminutes.api.helpers.dto;

import java.time.Instant;

public record BankChangeTokenResponse(String changeToken, Instant expiresAt) {}

