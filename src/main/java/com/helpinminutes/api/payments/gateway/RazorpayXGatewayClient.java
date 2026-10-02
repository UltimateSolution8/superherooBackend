package com.helpinminutes.api.payments.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RazorpayX over plain REST.
 *
 * <p>No SDK: {@code razorpay-java} covers payments, not payouts, and the payout API
 * is four endpoints. Structured like {@link RazorpayGatewayClient} on purpose — one
 * long-lived {@link HttpClient}, {@code isConfigured()} guarding every call, one
 * typed exception — so the two read the same when something goes wrong at 2am.
 *
 * <p>Unconfigured by default. With empty credentials every method throws before it
 * can reach the network, which is what lets the whole payout feature ship dark.
 */
@Component
public class RazorpayXGatewayClient implements RazorpayXGateway {
  private static final Logger log = LoggerFactory.getLogger(RazorpayXGatewayClient.class);
  private static final String BASE_URL = "https://api.razorpay.com/v1";

  private final String keyId;
  private final String keySecret;
  private final String accountNumber;
  private final String webhookSecret;

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  public RazorpayXGatewayClient(
      @Value("${razorpayx.key-id:}") String keyId,
      @Value("${razorpayx.key-secret:}") String keySecret,
      @Value("${razorpayx.account-number:}") String accountNumber,
      @Value("${razorpayx.webhook-secret:}") String webhookSecret) {
    this.keyId = trim(keyId);
    this.keySecret = trim(keySecret);
    this.accountNumber = trim(accountNumber);
    this.webhookSecret = trim(webhookSecret);
  }

  @Override
  public boolean isConfigured() {
    // The source account number matters as much as the credentials: RazorpayX pays
    // *from* a specific virtual account, and without it every payout is rejected.
    return !keyId.isBlank() && !keySecret.isBlank() && !accountNumber.isBlank();
  }

  @Override
  public String ensureContact(String referenceId, String name, String phone, String email) {
    JSONObject body = new JSONObject()
        .put("name", blankTo(name, "Partner"))
        .put("type", "vendor")
        .put("reference_id", referenceId);
    if (phone != null && !phone.isBlank()) body.put("contact", phone);
    if (email != null && !email.isBlank()) body.put("email", email);
    return post("/contacts", body, null).optString("id", null);
  }

  @Override
  public String ensureFundAccount(
      String contactId, String accountHolderName, String accountNumber, String ifsc) {
    JSONObject body = new JSONObject()
        .put("contact_id", contactId)
        .put("account_type", "bank_account")
        .put("bank_account", new JSONObject()
            .put("name", accountHolderName)
            .put("ifsc", ifsc)
            .put("account_number", accountNumber));
    return post("/fund_accounts", body, null).optString("id", null);
  }

  @Override
  public PayoutResult createPayout(
      String fundAccountId, long amountPaise, String purpose, String narration, String idempotencyKey) {
    JSONObject body = new JSONObject()
        .put("account_number", accountNumber)
        .put("fund_account_id", fundAccountId)
        .put("amount", amountPaise)
        .put("currency", "INR")
        // IMPS settles in minutes and is the right default for a gig payout; RazorpayX
        // falls back to NEFT itself when IMPS is unavailable for the destination.
        .put("mode", "IMPS")
        .put("purpose", blankTo(purpose, "payout"))
        .put("queue_if_low_balance", true)
        .put("narration", truncate(blankTo(narration, "Superherooo payout"), 30));
    return toResult(post("/payouts", body, idempotencyKey));
  }

  @Override
  public PayoutResult fetchPayout(String payoutId) {
    return toResult(get("/payouts/" + payoutId));
  }


  @Override
  public FundAccountValidationResult createFundAccountValidation(
      String fundAccountId, String currency) {
    JSONObject body = new JSONObject()
        .put("account_number", accountNumber)
        .put("fund_account", new JSONObject().put("id", fundAccountId))
        // ₹1 in paise. Razorpay only accepts 100 here; the amount is not a choice.
        .put("amount", 100)
        .put("currency", blankTo(currency, "INR"))
        // "optimized" activates the Hybrid FAV stack: penniless first, with automatic
        // penny-drop fallback. Gives ~3-second results at 99%+ success rate.
        .put("validation_type", "optimized")
        .put("notes", new JSONObject().put("purpose", "bank account verification"));
    return toValidationResult(post("/fund_accounts/validations", body, null));
  }

  @Override
  public FundAccountValidationResult createCompositeValidation(CompositeValidationRequest req) {
    // Composite API: creates Contact + Fund Account + Validation in a single call.
    // This is the preferred path for new verifications — fewer round trips, and it
    // lets RazorpayX run the hybrid stack atomically.
    JSONObject contactJson = new JSONObject()
        .put("name", blankTo(req.contactName(), "Partner"))
        .put("type", "vendor")
        .put("reference_id", req.referenceId());
    if (req.contactPhone() != null && !req.contactPhone().isBlank()) {
      contactJson.put("contact", req.contactPhone());
    }
    if (req.contactEmail() != null && !req.contactEmail().isBlank()) {
      contactJson.put("email", req.contactEmail());
    }

    JSONObject fundAccountJson = new JSONObject()
        .put("account_type", req.accountType())
        .put("contact", contactJson);

    if ("vpa".equals(req.accountType())) {
      fundAccountJson.put("vpa", new JSONObject().put("address", req.vpaAddress()));
    } else {
      // bank_account is the default
      fundAccountJson.put("bank_account", new JSONObject()
          .put("name", blankTo(req.accountHolderName(), req.contactName()))
          .put("ifsc", req.ifsc())
          .put("account_number", req.accountNumber()));
    }

    JSONObject body = new JSONObject()
        .put("source_account_number", accountNumber)
        .put("validation_type", blankTo(req.validationType(), "optimized"))
        .put("fund_account", fundAccountJson)
        .put("notes", new JSONObject().put("purpose", "account verification"));

    return toValidationResult(post("/fund_accounts/validations", body, null));
  }

  @Override
  public FundAccountValidationResult fetchFundAccountValidation(String validationId) {
    return toValidationResult(get("/fund_accounts/validations/" + validationId));
  }

  @Override
  public boolean verifyWebhookSignature(String rawBody, String signature) {
    if (webhookSecret.isBlank()) {
      throw new RazorpayGatewayException("RazorpayX webhook secret is not configured");
    }
    if (signature == null || signature.isBlank()) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(expected.length * 2);
      for (byte b : expected) hex.append(String.format("%02x", b));
      // Constant-time: a timing side channel here leaks the secret one byte at a time.
      return java.security.MessageDigest.isEqual(
          hex.toString().getBytes(StandardCharsets.UTF_8),
          signature.trim().getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.warn("RazorpayX webhook signature check failed: {}", e.getMessage());
      return false;
    }
  }

  private JSONObject post(String path, JSONObject body, String idempotencyKey) {
    ensureConfigured();
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", basicAuth())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    // RazorpayX deduplicates on this header for 24 hours. Without it a socket timeout
    // on a payout is indistinguishable from a failure, and the retry pays again.
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      request.header("X-Payout-Idempotency", idempotencyKey);
    }
    return send(request.build(), path);
  }

  private JSONObject get(String path) {
    ensureConfigured();
    return send(HttpRequest.newBuilder(URI.create(BASE_URL + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", basicAuth())
        .GET()
        .build(), path);
  }

  private JSONObject send(HttpRequest request, String path) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        // The body can echo bank details, so only the code and RazorpayX's own error
        // description are logged.
        String description = errorDescription(response.body());
        throw new RazorpayGatewayException(
            "RazorpayX " + path + " returned HTTP " + response.statusCode()
                + (description == null ? "" : " (" + description + ")"));
      }
      return new JSONObject(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RazorpayGatewayException("RazorpayX request was interrupted", e);
    } catch (RazorpayGatewayException e) {
      throw e;
    } catch (Exception e) {
      throw new RazorpayGatewayException("RazorpayX request failed", e);
    }
  }

  private static String errorDescription(String body) {
    try {
      return new JSONObject(body).getJSONObject("error").optString("description", null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static FundAccountValidationResult toValidationResult(JSONObject validation) {
    // The bank's registered name and provider name match score live one level down,
    // on the results object, and are absent until the drop completes.
    JSONObject results = validation.optJSONObject("results");
    // Webhook uses "results"; polling response uses "validation_results".
    if (results == null) results = validation.optJSONObject("validation_results");
    String registeredName = results == null ? null : results.optString("registered_name", null);
    Integer nameMatchScore = null;
    if (results != null && results.has("name_match_score") && !results.isNull("name_match_score")) {
      nameMatchScore = results.optInt("name_match_score");
    }

    // Structured failure info lives in status_details.
    JSONObject statusDetails = validation.optJSONObject("status_details");
    String failureSource = statusDetails == null ? null : statusDetails.optString("source", null);
    String failureReasonCode = statusDetails == null ? null : statusDetails.optString("reason", null);
    // Prefer status_details.description as the human reason; fall back to the legacy field.
    String failureReason = statusDetails == null
        ? validation.optString("error_description", null)
        : statusDetails.optString("description", validation.optString("error_description", null));

    // account type from the nested fund_account object
    JSONObject fundAccount = validation.optJSONObject("fund_account");
    String accountType = fundAccount == null ? "bank_account" : fundAccount.optString("account_type", "bank_account");

    return new FundAccountValidationResult(
        validation.optString("id", null),
        validation.optString("status", "unknown"),
        registeredName,
        validation.optString("utr", null),
        validation.optLong("amount", 0L),
        failureReason,
        nameMatchScore,
        accountType,
        failureSource,
        failureReasonCode);
  }

  private static PayoutResult toResult(JSONObject payout) {
    return new PayoutResult(
        payout.optString("id", null),
        payout.optLong("amount", 0L),
        payout.optString("status", "unknown"),
        payout.optString("utr", null),
        payout.optString("failure_reason", null));
  }

  private String basicAuth() {
    return "Basic " + Base64.getEncoder().encodeToString(
        (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
  }

  private void ensureConfigured() {
    if (!isConfigured()) {
      throw new RazorpayGatewayException("RazorpayX is not configured");
    }
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
