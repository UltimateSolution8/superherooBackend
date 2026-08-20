package com.helpinminutes.api.users.service.email;

import com.helpinminutes.api.config.AppProperties;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resend Email OTP Provider: Delivers one-time passcodes via the Resend HTTPS API.
 * Uses local constant-time verification.
 */
@Component
@Order(5)
public class ResendEmailOtpSender implements EmailOtpSender {
  private static final Logger log = LoggerFactory.getLogger(ResendEmailOtpSender.class);
  private static final SecureRandom RNG = new SecureRandom();

  private final ResendClient resendClient;
  private final AppProperties props;

  public ResendEmailOtpSender(ResendClient resendClient, AppProperties props) {
    this.resendClient = resendClient;
    this.props = props;
  }

  @Override
  public String providerId() {
    return "resend";
  }

  @Override
  public boolean isConfigured() {
    return resendClient.isConfigured();
  }

  @Override
  public EmailOtpDispatch send(String email) {
    String otp = String.format("%06d", RNG.nextInt(1_000_000));
    long ttlMinutes = Math.max(1L, props.otp().ttlSeconds() / 60L);

    String subject = "Your Superherooo verification code: " + otp;
    String html = String.format("""
        <div style="font-family: Arial, sans-serif; max-width: 500px; margin: 0 auto; padding: 24px; border: 1px solid #E2E8F0; border-radius: 12px; background-color: #FFFFFF;">
          <h2 style="color: #0F1932; margin-bottom: 8px;">Superherooo Verification Code</h2>
          <p style="color: #64748B; font-size: 15px; margin-bottom: 20px;">Use the following 6-digit one-time code to complete your verification:</p>
          <div style="margin: 20px 0; padding: 16px; background-color: #F8FAFC; border-radius: 8px; text-align: center; border: 1px solid #CBD5E1;">
            <span style="font-size: 32px; font-weight: 800; letter-spacing: 6px; color: #1E3A8A;">%s</span>
          </div>
          <p style="color: #64748B; font-size: 13px; margin-top: 20px;">This code is valid for %d minutes. If you did not request this, please ignore this email.</p>
        </div>
        """, otp, ttlMinutes);

    boolean sent = resendClient.sendEmail(email, subject, html);
    if (!sent) {
      log.warn("Resend email delivery failed for {}; falling through to next email sender", email);
      return null;
    }

    log.info("Email verification OTP successfully dispatched via Resend to {}", email);
    return EmailOtpDispatch.local(otp);
  }

  @Override
  public boolean verify(String email, String state, String submittedOtp) {
    if (state == null || submittedOtp == null) return false;
    return MessageDigest.isEqual(
        state.getBytes(StandardCharsets.UTF_8),
        submittedOtp.trim().getBytes(StandardCharsets.UTF_8));
  }
}
