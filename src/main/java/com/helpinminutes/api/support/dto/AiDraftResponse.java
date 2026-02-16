package com.helpinminutes.api.support.dto;

public record AiDraftResponse(
    boolean enabled,
    String draft,
    String reason
) {
  public static AiDraftResponse disabled(String reason) {
    return new AiDraftResponse(false, null, reason);
  }

  public static AiDraftResponse enabled(String draft) {
    return new AiDraftResponse(true, draft, null);
  }
}

