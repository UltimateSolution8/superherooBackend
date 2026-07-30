package com.helpinminutes.api.users.service.email;

/**
 * The outcome of sending an email OTP.
 *
 * @param state what to persist so the code can be verified later
 * @param plaintextOtp the issued code when this provider verifies locally, or
 *     {@code null} when the provider owns verification. Only ever surfaced to
 *     the client when {@code app.otp.returnOtpInResponse} is enabled, which is
 *     a local-development setting.
 */
public record EmailOtpDispatch(String state, String plaintextOtp) {

  /** The provider generated, sent and will verify the code. */
  public static EmailOtpDispatch delegated(String providerStateId) {
    return new EmailOtpDispatch(providerStateId, null);
  }

  /** We generated the code and the provider merely delivered the message. */
  public static EmailOtpDispatch local(String otp) {
    return new EmailOtpDispatch(otp, otp);
  }
}
