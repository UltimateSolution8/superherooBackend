package com.helpinminutes.api.users.service.email;

import com.helpinminutes.api.config.AppProperties;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Local provider: we generate and verify the code; SMTP only delivers it.
 *
 * This is the last link in the chain, and the template Amazon SES should follow
 * when it replaces SMTP — implement {@link EmailOtpSender}, return
 * {@link EmailOtpDispatch#local}, and give it a lower {@code @Order} than this.
 */
@Component
@Order(100)
public class SmtpEmailOtpSender implements EmailOtpSender {
  private static final Logger log = LoggerFactory.getLogger(SmtpEmailOtpSender.class);
  private static final SecureRandom RNG = new SecureRandom();

  private final ObjectProvider<JavaMailSender> mailSender;
  private final AppProperties props;

  public SmtpEmailOtpSender(ObjectProvider<JavaMailSender> mailSender, AppProperties props) {
    this.mailSender = mailSender;
    this.props = props;
  }

  @Override
  public String providerId() {
    return "local";
  }

  @Override
  public boolean isConfigured() {
    // Always the terminal fallback: even with no mail server we still issue a
    // code so the verify step behaves consistently. Delivery failure is logged.
    return true;
  }

  @Override
  public EmailOtpDispatch send(String email) {
    String otp = String.format("%06d", RNG.nextInt(1_000_000));
    deliver(email, otp);
    return EmailOtpDispatch.local(otp);
  }

  @Override
  public boolean verify(String email, String state, String submittedOtp) {
    // Constant-time comparison: the code is a short numeric secret.
    return java.security.MessageDigest.isEqual(
        state.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        submittedOtp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private void deliver(String email, String otp) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null) {
      log.warn("No JavaMailSender configured; email verification code was generated but not sent");
      return;
    }
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setTo(email);
      message.setSubject("Your Superherooo verification code");
      message.setText("Your Superherooo verification code is " + otp + ". It is valid for "
          + Math.max(1, props.otp().ttlSeconds() / 60) + " minutes.\n\n"
          + "If you did not request this, you can ignore this email.");
      sender.send(message);
      log.info("Email verification code sent via SMTP");
    } catch (Exception e) {
      log.warn("SMTP delivery of the email verification code failed: {}", e.getMessage());
    }
  }
}
