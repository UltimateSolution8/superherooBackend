package com.helpinminutes.api.payments.gateway;

import java.util.Map;

public interface RazorpayGateway {
  boolean isConfigured();
  String keyId();
  OrderResult createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes);
  PaymentResult fetchPayment(String paymentId);
  PaymentResult capturePayment(String paymentId, long amountPaise, String currency);
  boolean verifyPaymentSignature(String storedOrderId, String paymentId, String signature);
  boolean verifyWebhookSignature(String rawBody, String signature);

  record OrderResult(String id, long amountPaise, String currency, String status) {}

  record PaymentResult(
      String id,
      String orderId,
      long amountPaise,
      String currency,
      String status,
      String method,
      long amountRefundedPaise,
      String errorCode,
      String errorDescription) {}
}
