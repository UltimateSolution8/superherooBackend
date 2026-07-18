package com.helpinminutes.api.tasks.dto;

import com.helpinminutes.api.tasks.model.TaskUrgency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;

public record CreateTaskRequest(
    @NotBlank String title,
    @NotBlank String description,
    @NotNull TaskUrgency urgency,
    @NotNull @Min(1) @Max(1440) Integer timeMinutes,
    @NotNull @Min(100) Long budgetPaise,
    @Min(-90) @Max(90) double lat,
    @Min(-180) @Max(180) double lng,
    String addressText,
    Instant scheduledAt,
    String landmark,
    PaymentCollectionMode paymentCollectionMode
) {
  public CreateTaskRequest(
      String title,
      String description,
      TaskUrgency urgency,
      Integer timeMinutes,
      Long budgetPaise,
      double lat,
      double lng,
      String addressText,
      Instant scheduledAt,
      String landmark) {
    this(title, description, urgency, timeMinutes, budgetPaise, lat, lng, addressText,
        scheduledAt, landmark, PaymentCollectionMode.PAY_AFTER_SERVICE);
  }
}
