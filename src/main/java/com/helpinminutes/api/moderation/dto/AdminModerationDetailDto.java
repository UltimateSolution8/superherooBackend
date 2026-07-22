package com.helpinminutes.api.moderation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminModerationDetailDto(
    UUID taskId,
    UUID buyerId,
    String customerName,
    String customerPhone,
    String customerEmail,
    String title,
    String description,
    String category,
    Long budgetPaise,
    String addressText,
    String landmark,
    double lat,
    double lng,
    String status,
    Instant createdAt,
    // AI Review fields
    String aiModel,
    String aiStatus,
    Integer confidence,
    Integer riskScore,
    Integer qualityScore,
    List<String> reasons,
    List<String> flags,
    String rawAiResponse,
    Long reviewDurationMs,
    Instant aiReviewedAt,
    // Audit log history
    List<AuditLogDto> auditHistory
) {
  public record AuditLogDto(
      UUID id,
      String action,
      String performedBy,
      Instant timestamp,
      String remarks
  ) {}
}
