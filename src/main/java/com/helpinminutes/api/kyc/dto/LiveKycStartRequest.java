package com.helpinminutes.api.kyc.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LiveKycStartRequest(@NotNull UUID helperId) {}
