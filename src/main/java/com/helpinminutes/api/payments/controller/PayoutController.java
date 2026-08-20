package com.helpinminutes.api.payments.controller;

import com.helpinminutes.api.payments.dto.PayoutDtos.PayoutItemResponse;
import com.helpinminutes.api.payments.dto.PayoutDtos.PayoutRequest;
import com.helpinminutes.api.payments.dto.PayoutDtos.PayoutSummary;
import com.helpinminutes.api.payments.service.PayoutReconciliationJob;
import com.helpinminutes.api.payments.service.PayoutService;
import com.helpinminutes.api.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner earnings and withdrawals.
 *
 * <p>The summary is always readable — a partner can see what they have earned during
 * the cash-only period, which is most of the value — but {@code /request} refuses
 * with a 503 while {@code app.payments.payoutsEnabled} is false.
 */
@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {
  private final PayoutService payouts;
  private final PayoutReconciliationJob reconciliation;

  private final com.helpinminutes.api.payments.service.PayoutAccountValidationService validations;

  public PayoutController(
      PayoutService payouts,
      PayoutReconciliationJob reconciliation,
      com.helpinminutes.api.payments.service.PayoutAccountValidationService validations) {
    this.payouts = payouts;
    this.reconciliation = reconciliation;
    this.validations = validations;
  }

  /**
   * Starts a penny drop against the partner's bank account.
   *
   * <p>Idempotent while one is in flight, and capped per account per day — each
   * drop is a real ₹1 transfer plus a fee.
   */
  @PostMapping("/me/account/verify")
  public BankVerificationView startVerification(@AuthenticationPrincipal UserPrincipal principal) {
    return BankVerificationView.of(validations.startValidation(principal.userId()));
  }

  @GetMapping("/me/account/verification")
  public BankVerificationView verification(@AuthenticationPrincipal UserPrincipal principal) {
    return validations
        .latestFor(principal.userId())
        .map(BankVerificationView::of)
        .orElse(BankVerificationView.notStarted());
  }

  /**
   * What the app shows about bank verification.
   *
   * <p>{@code registeredName} is deliberately absent: it is the name on someone's
   * bank account, and echoing it back would confirm account ownership details to
   * whoever holds the session.
   */
  public record BankVerificationView(
      String status, String failureReason, java.time.Instant updatedAt) {

    static BankVerificationView of(
        com.helpinminutes.api.payments.model.PayoutAccountValidationEntity v) {
      return new BankVerificationView(v.getStatus(), v.getFailureReason(), v.getUpdatedAt());
    }

    static BankVerificationView notStarted() {
      return new BankVerificationView("NOT_STARTED", null, null);
    }
  }

  @GetMapping("/me")
  public PayoutSummary summary(@AuthenticationPrincipal UserPrincipal principal) {
    return payouts.summary(principal.userId());
  }

  @GetMapping("/me/history")
  public List<PayoutItemResponse> history(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(defaultValue = "20") int limit) {
    return payouts.history(principal.userId(), Math.min(limit, 100));
  }

  @PostMapping("/request")
  public PayoutItemResponse request(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody(required = false) PayoutRequest request) {
    return payouts.requestPayout(
        principal.userId(), principal.role(), request == null ? null : request.amountPaise());
  }

  /**
   * RazorpayX calls this. Unauthenticated by necessity and verified by signature —
   * see {@code SecurityConfig}, which permits it alongside the payment webhook.
   */
  @PostMapping("/webhooks/razorpayx")
  public ResponseEntity<Void> webhook(
      @RequestBody String rawBody,
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
      @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
    reconciliation.handleWebhook(rawBody, signature, eventId);
    return ResponseEntity.noContent().build();
  }
}
