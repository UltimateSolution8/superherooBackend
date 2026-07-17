package com.helpinminutes.api.admin.dto;

import java.time.Instant;
import java.util.List;

public record AdminActionCenterResponse(
    long actionCount,
    List<ActionItem> items,
    Instant generatedAt) {

  public record ActionItem(
      String type,
      String title,
      String description,
      long count,
      String href,
      String severity) {}
}
