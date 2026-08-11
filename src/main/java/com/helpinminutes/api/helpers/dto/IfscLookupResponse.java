package com.helpinminutes.api.helpers.dto;

public record IfscLookupResponse(
    String ifsc,
    String bankName,
    String branch,
    String city,
    String district,
    String state
) {}
