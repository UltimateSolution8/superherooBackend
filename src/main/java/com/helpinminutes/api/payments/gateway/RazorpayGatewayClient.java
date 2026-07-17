package com.helpinminutes.api.payments.gateway;

import com.razorpay.Order;
import com.razorpay.Payment;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import java.util.Map;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RazorpayGatewayClient implements RazorpayGateway {
  private final String keyId;
  private final String keySecret;
  private final String webhookSecret;
  private volatile RazorpayClient client;

  public RazorpayGatewayClient(
      @Value("${razorpay.key-id:}") String keyId,
      @Value("${razorpay.key-secret:}") String keySecret,
      @Value("${razorpay.webhook-secret:}") String webhookSecret) {
    this.keyId = keyId == null ? "" : keyId.trim();
    this.keySecret = keySecret == null ? "" : keySecret.trim();
    this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
  }

  @Override
  public boolean isConfigured() {
    return !keyId.isBlank() && !keySecret.isBlank();
  }

  @Override
  public String keyId() {
    ensureConfigured();
    return keyId;
  }

  @Override
  public OrderResult createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes) {
    try {
      JSONObject request = new JSONObject();
      request.put("amount", amountPaise);
      request.put("currency", currency);
      request.put("receipt", receipt);
      request.put("partial_payment", false);
      request.put("notes", new JSONObject(notes));
      Order order = client().orders.create(request);
      return new OrderResult(
          order.get("id"),
          number(order.get("amount")),
          order.get("currency"),
          order.get("status"));
    } catch (RazorpayException | RuntimeException e) {
      throw new RazorpayGatewayException("Razorpay order creation failed", e);
    }
  }

  @Override
  public PaymentResult fetchPayment(String paymentId) {
    try {
      return toPaymentResult(client().payments.fetch(paymentId));
    } catch (RazorpayException | RuntimeException e) {
      throw new RazorpayGatewayException("Razorpay payment lookup failed", e);
    }
  }

  @Override
  public PaymentResult capturePayment(String paymentId, long amountPaise, String currency) {
    try {
      JSONObject request = new JSONObject();
      request.put("amount", amountPaise);
      request.put("currency", currency);
      return toPaymentResult(client().payments.capture(paymentId, request));
    } catch (RazorpayException | RuntimeException e) {
      throw new RazorpayGatewayException("Razorpay payment capture failed", e);
    }
  }

  @Override
  public boolean verifyPaymentSignature(String storedOrderId, String paymentId, String signature) {
    try {
      JSONObject fields = new JSONObject();
      fields.put("razorpay_order_id", storedOrderId);
      fields.put("razorpay_payment_id", paymentId);
      fields.put("razorpay_signature", signature);
      return Utils.verifyPaymentSignature(fields, keySecret);
    } catch (RazorpayException | RuntimeException e) {
      return false;
    }
  }

  @Override
  public boolean verifyWebhookSignature(String rawBody, String signature) {
    if (webhookSecret.isBlank()) {
      throw new RazorpayGatewayException("Razorpay webhook secret is not configured");
    }
    try {
      return Utils.verifyWebhookSignature(rawBody, signature, webhookSecret);
    } catch (RazorpayException | RuntimeException e) {
      return false;
    }
  }

  private PaymentResult toPaymentResult(Payment payment) {
    JSONObject json = payment.toJson();
    return new PaymentResult(
        payment.get("id"),
        payment.get("order_id"),
        number(payment.get("amount")),
        payment.get("currency"),
        payment.get("status"),
        payment.get("method"),
        json.optLong("amount_refunded", 0L),
        nullableString(json, "error_code"),
        nullableString(json, "error_description"));
  }

  private RazorpayClient client() {
    ensureConfigured();
    RazorpayClient value = client;
    if (value != null) return value;
    synchronized (this) {
      if (client == null) {
        try {
          client = new RazorpayClient(keyId, keySecret);
        } catch (RazorpayException e) {
          throw new RazorpayGatewayException("Razorpay client initialization failed", e);
        }
      }
      return client;
    }
  }

  private void ensureConfigured() {
    if (!isConfigured()) {
      throw new RazorpayGatewayException("Razorpay is not configured");
    }
  }

  private static long number(Object value) {
    return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
  }

  private static String nullableString(JSONObject json, String key) {
    return json.has(key) && !json.isNull(key) ? json.optString(key, null) : null;
  }
}
