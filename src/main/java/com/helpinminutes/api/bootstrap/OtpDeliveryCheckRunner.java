package com.helpinminutes.api.bootstrap;

import com.helpinminutes.api.config.ExotelProperties;
import com.helpinminutes.api.config.Msg91Properties;
import com.helpinminutes.api.config.ReviewerPhoneProperties;
import com.helpinminutes.api.config.TwilioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Says loudly, at boot, whether an OTP can actually reach a handset.
 *
 * <p>Phone plus OTP is the primary way into all three apps. Every SMS provider is
 * optional configuration, and when none of them is fully configured the service still
 * starts, still generates codes, still stores them in Redis — and delivers nothing.
 * Sign-in is then broken for every user, and the only symptom is silence.
 *
 * <p>This used to be masked: {@code app.otp.return-otp-in-response} echoed the code
 * back to the caller, so an unconfigured environment looked like it worked. That flag
 * was an authentication bypass and is gone, which makes an explicit check at startup
 * the thing that replaces it.
 */
@Component
@Order(5) // before the seeding runners, so the warning is near the top of the log
public class OtpDeliveryCheckRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(OtpDeliveryCheckRunner.class);

  private final Msg91Properties msg91;
  private final ExotelProperties exotel;
  private final TwilioProperties twilio;
  private final ReviewerPhoneProperties reviewerPhones;

  public OtpDeliveryCheckRunner(
      Msg91Properties msg91,
      ExotelProperties exotel,
      TwilioProperties twilio,
      ReviewerPhoneProperties reviewerPhones) {
    this.msg91 = msg91;
    this.exotel = exotel;
    this.twilio = twilio;
    this.reviewerPhones = reviewerPhones;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (msg91 != null && msg91.canSendSms()) {
      log.info("OTP delivery: MSG91. Auto-read needs the template to end with ##var3## "
          + "(the SMS Retriever app hash) and the whole message to stay under 140 bytes.");
      return;
    }
    if (msg91 != null && msg91.enabled()) {
      log.warn("MSG91 is enabled but MSG91_AUTH_KEY or MSG91_TEMPLATE_ID is missing — it cannot send.");
    }
    if (exotel != null && exotel.canSendSms()) {
      log.info("OTP delivery: Exotel. Note that Exotel messages carry no SMS Retriever hash, "
          + "so zero-touch auto-read will not fire — only the keyboard suggestion.");
      return;
    }
    if (twilio != null && twilio.enabled()) {
      log.info("OTP delivery: Twilio Verify.");
      return;
    }

    log.error("NO SMS PROVIDER IS CONFIGURED. OTP codes will be generated and stored but never "
        + "delivered, so phone sign-in is broken for every user. Set MSG91_AUTH_KEY and "
        + "MSG91_TEMPLATE_ID (or the Exotel/Twilio equivalents).");
    if (!reviewerPhones.isEmpty()) {
      log.error("Only the allowlisted reviewer numbers can sign in right now.");
    }
  }
}
