package com.helpinminutes.api.chatbot.dto;

import java.util.List;

public record ChatbotResponse(
    String reply,
    String model,
    List<QuickAction> suggestedActions
) {
  public record QuickAction(
      String label,
      String link
  ) {}
}
