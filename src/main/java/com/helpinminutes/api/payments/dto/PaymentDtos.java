package com.helpinminutes.api.payments.dto;

import com.helpinminutes.api.batches.model.BatchPaymentMode;
import com.helpinminutes.api.payments.model.PaymentScope;
import com.helpinminutes.api.payments.model.PaymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {
  private PaymentDtos() {}

  public record CreateOrderResponse(
      UUID paymentId,
      UUID taskId,
      UUID batchId,
      PaymentScope paymentScope,
      String keyId,
      String orderId,
      long amount,
      String currency,
      String receipt,
      PaymentStatus status,
      boolean alreadyPaid,
      CheckoutPrefill prefill) {}

  public record CheckoutPrefill(String name, String email, String contact) {}

  public record VerifyPaymentRequest(
      UUID taskId,
      UUID batchId,
      @NotBlank @Size(max = 128) String razorpayOrderId,
      @NotBlank @Size(max = 128) String razorpayPaymentId,
      @NotBlank @Size(max = 256) String razorpaySignature) {
    public VerifyPaymentRequest(
        UUID taskId,
        String razorpayOrderId,
        String razorpayPaymentId,
        String razorpaySignature) {
      this(taskId, null, razorpayOrderId, razorpayPaymentId, razorpaySignature);
    }
  }

  public record SelectBatchPaymentModeRequest(@NotNull BatchPaymentMode mode) {}

  public record BatchPaymentLine(
      UUID taskId,
      UUID helperId,
      String helperName,
      long amountPaise,
      String taskStatus,
      PaymentResponse payment) {}

  public record BatchPaymentSummary(
      UUID batchId,
      String batchTitle,
      BatchPaymentMode mode,
      boolean completed,
      boolean modeLocked,
      long totalAmountPaise,
      PaymentResponse consolidatedPayment,
      java.util.List<BatchPaymentLine> helperPayments) {}

  public record PaymentResponse(
      UUID id,
      UUID taskId,
      UUID batchId,
      PaymentScope paymentScope,
      String taskTitle,
      long amountPaise,
      String currency,
      String provider,
      String method,
      PaymentStatus status,
      String providerPaymentId,
      long amountRefundedPaise,
      Instant paidAt,
      Instant capturedAt,
      Instant createdAt,
      Instant updatedAt,
      boolean paid) {}
}
