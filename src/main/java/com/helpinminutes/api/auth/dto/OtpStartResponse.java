package com.helpinminutes.api.auth.dto;

/**
 * The code itself is never returned.
 *
 * A {@code devOtp} field used to carry it whenever {@code app.otp.returnOtpInResponse}
 * was on, and the app auto-submitted from it. That is an authentication bypass with a
 * config flag in front of it: anyone who could reach this endpoint could sign in as
 * any phone number. Removed rather than defaulted off — a flag that would be a
 * catastrophe if flipped is not a flag.
 */
public record OtpStartResponse(
    String phone,
    boolean sent
) {}
