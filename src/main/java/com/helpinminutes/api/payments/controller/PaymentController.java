package com.helpinminutes.api.payments.controller;

import com.helpinminutes.api.payments.dto.PaymentDtos.CreateOrderResponse;
import com.helpinminutes.api.payments.dto.PaymentDtos.BatchPaymentSummary;
import com.helpinminutes.api.payments.dto.PaymentDtos.PaymentResponse;
import com.helpinminutes.api.payments.dto.PaymentDtos.SelectBatchPaymentModeRequest;
import com.helpinminutes.api.payments.dto.PaymentDtos.VerifyPaymentRequest;
import com.helpinminutes.api.payments.dto.PaymentDtos.DirectPaymentRequest;
import com.helpinminutes.api.payments.service.PaymentService;
import com.helpinminutes.api.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/tasks/{taskId}/orders")
  public CreateOrderResponse createTaskOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return payments.createTaskOrder(principal.userId(), principal.role(), taskId, idempotencyKey);
  }

  @PostMapping("/batches/{batchId}/orders")
  public CreateOrderResponse createBatchOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return payments.createBatchOrder(principal.userId(), principal.role(), batchId, idempotencyKey);
  }

  @PostMapping("/batches/{batchId}/mode")
  public BatchPaymentSummary selectBatchPaymentMode(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @Valid @RequestBody SelectBatchPaymentModeRequest request) {
    return payments.selectBatchPaymentMode(principal.userId(), principal.role(), batchId, request.mode());
  }

  @PostMapping("/verify")
  public PaymentResponse verify(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payments.verify(principal.userId(), principal.role(), request);
  }

  @PostMapping("/tasks/{taskId}/direct-payment")
  public PaymentResponse confirmDirectPayment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody DirectPaymentRequest request) {
    return payments.confirmDirectPayment(
        principal.userId(), principal.role(), taskId, request.method());
  }

  @GetMapping("/tasks/{taskId}")
  public ResponseEntity<PaymentResponse> taskPayment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId) {
    return ResponseEntity.ofNullable(payments.getForTask(principal.userId(), principal.role(), taskId));
  }

  @GetMapping("/batches/{batchId}")
  public ResponseEntity<PaymentResponse> batchPayment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    return ResponseEntity.ofNullable(payments.getForBatch(principal.userId(), principal.role(), batchId));
  }

  @GetMapping("/batches/{batchId}/summary")
  public BatchPaymentSummary batchPaymentSummary(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    return payments.batchPaymentSummary(principal.userId(), principal.role(), batchId);
  }

  @GetMapping("/me")
  public List<PaymentResponse> history(@AuthenticationPrincipal UserPrincipal principal) {
    return payments.history(principal.userId(), principal.role());
  }

  @PostMapping("/webhooks/razorpay")
  public ResponseEntity<Void> razorpayWebhook(
      @RequestBody String rawBody,
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
      @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
    payments.processWebhook(rawBody, signature, eventId);
    return ResponseEntity.noContent().build();
  }
}
