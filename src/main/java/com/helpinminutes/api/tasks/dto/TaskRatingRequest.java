package com.helpinminutes.api.tasks.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TaskRatingRequest(
    @NotNull @DecimalMin("1.0") @DecimalMax("5.0") BigDecimal rating,
    String comment
) {}
