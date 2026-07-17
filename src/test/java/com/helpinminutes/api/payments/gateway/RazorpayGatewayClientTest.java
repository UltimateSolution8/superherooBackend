package com.helpinminutes.api.payments.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class RazorpayGatewayClientTest {
  @Test
  void verifiesPaymentSignatureUsingServerSecret() throws Exception {
    String secret = "test_secret";
    String orderId = "order_server_owned";
    String paymentId = "pay_checkout_result";
    RazorpayGatewayClient client = new RazorpayGatewayClient("rzp_test_key", secret, "webhook_secret");

    String signature = hmac(orderId + "|" + paymentId, secret);

    assertTrue(client.verifyPaymentSignature(orderId, paymentId, signature));
    assertFalse(client.verifyPaymentSignature(orderId, paymentId, "tampered"));
  }

  @Test
  void verifiesWebhookAgainstRawBody() throws Exception {
    String secret = "webhook_secret";
    String body = "{\"event\":\"payment.captured\"}";
    RazorpayGatewayClient client = new RazorpayGatewayClient("rzp_test_key", "test_secret", secret);

    assertTrue(client.verifyWebhookSignature(body, hmac(body, secret)));
    assertFalse(client.verifyWebhookSignature(body + " ", hmac(body, secret)));
  }

  private static String hmac(String payload, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
  }
}
