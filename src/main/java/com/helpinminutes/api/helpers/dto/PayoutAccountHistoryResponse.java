package com.helpinminutes.api.helpers.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PayoutAccountHistoryResponse(UUID userId, List<Entry> entries) {
  public record Entry(
      UUID eventId,
      String actionType,
      String changeSource,
      UUID previousAccountId,
      UUID newAccountId,
      String previousMaskedAccount,
      String newMaskedAccount,
      String previousBankName,
      String newBankName,
      String previousIfscCode,
      String newIfscCode,
      UUID actorUserId,
      String actorRole,
      String ipAddress,
      Instant changedAt
  ) {}
}
