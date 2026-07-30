package com.helpinminutes.api.auth.dto;

/**
 * Intentionally identical whether or not the address has an account — a
 * different response would let an attacker enumerate registered users.
 *
 * @param devOtp only populated when app.otp.returnOtpInResponse is enabled,
 *     which is a local-development setting.
 */
public record ForgotPasswordResponse(String email, boolean sent, String devOtp) {}
