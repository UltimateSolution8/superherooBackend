package com.helpinminutes.api.auth.dto;

public record OtpStartResponse(
    String phone,
    boolean sent,
    String devOtp
) {}
