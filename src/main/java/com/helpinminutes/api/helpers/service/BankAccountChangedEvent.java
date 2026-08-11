package com.helpinminutes.api.helpers.service;

import java.util.UUID;

public record BankAccountChangedEvent(UUID userId, String bankName, String last4) {}

