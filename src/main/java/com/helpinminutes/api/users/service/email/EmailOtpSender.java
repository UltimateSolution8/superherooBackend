package com.helpinminutes.api.users.service.email;

/**
 * Delivers an email one-time passcode.
 *
 * Two provider shapes exist and both must be supported:
 *
 * <ul>
 *   <li><b>Delegated</b> — the provider owns the code (it generates, sends and
 *       verifies it) and hands us an opaque state id to quote back. MojoAuth
 *       works this way.</li>
 *   <li><b>Local</b> — the provider is a dumb pipe. We generate the code, ask
 *       the provider to deliver the message, and verify it ourselves. Plain
 *       SMTP works this way, and so will Amazon SES.</li>
 * </ul>
 *
 * Adding SES therefore means adding one class that returns
 * {@link EmailOtpDispatch#local} and registering it ahead of SMTP in
 * {@code EmailVerificationService} — no changes to callers or storage.
 */
public interface EmailOtpSender {

  /** Short stable identifier used in the persisted state and in logs. */
  String providerId();

  /** False when credentials are absent; the next sender in the chain is tried. */
  boolean isConfigured();

  /**
   * Sends a passcode to {@code email}.
   *
   * @return what to persist for the later verify step, or {@code null} if
   *     delivery failed and the next sender should be tried.
   */
  EmailOtpDispatch send(String email);

  /**
   * Verifies a submitted code.
   *
   * @param state the value stored by {@link #send} — a provider state id for
   *     delegated providers, or the issued code for local ones.
   */
  boolean verify(String email, String state, String submittedOtp);
}
