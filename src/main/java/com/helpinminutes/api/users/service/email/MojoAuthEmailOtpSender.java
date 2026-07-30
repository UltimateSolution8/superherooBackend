package com.helpinminutes.api.users.service.email;

import com.helpinminutes.api.users.service.MojoAuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Delegated provider: MojoAuth generates, delivers and verifies the code. We
 * only persist its opaque {@code state_id}.
 */
@Component
@Order(10)
public class MojoAuthEmailOtpSender implements EmailOtpSender {
  private static final Logger log = LoggerFactory.getLogger(MojoAuthEmailOtpSender.class);

  private final MojoAuthClient client;

  public MojoAuthEmailOtpSender(MojoAuthClient client) {
    this.client = client;
  }

  @Override
  public String providerId() {
    return "mojo";
  }

  @Override
  public boolean isConfigured() {
    return client.isConfigured();
  }

  @Override
  public EmailOtpDispatch send(String email) {
    try {
      String stateId = client.sendEmailOtp(email);
      if (stateId == null || stateId.isBlank()) {
        log.warn("MojoAuth returned no state id; falling through to the next email provider");
        return null;
      }
      return EmailOtpDispatch.delegated(stateId);
    } catch (Exception e) {
      log.warn("MojoAuth email OTP delivery failed, falling through: {}", e.getMessage());
      return null;
    }
  }

  @Override
  public boolean verify(String email, String state, String submittedOtp) {
    if (!isConfigured()) {
      // Credentials were removed between send and verify. Fail closed.
      return false;
    }
    try {
      return client.verifyEmailOtp(state, submittedOtp);
    } catch (Exception e) {
      log.warn("MojoAuth email OTP verification failed: {}", e.getMessage());
      return false;
    }
  }
}
