package com.helpinminutes.api.auth.dto;

/**
 * Intentionally identical whether or not the address has an account — a
 * different response would let an attacker enumerate registered users.
 *
 * <p>The code is never included; it goes to the inbox and nowhere else.
 */
public record ForgotPasswordResponse(String email, boolean sent) {}
