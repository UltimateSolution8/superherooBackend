package com.helpinminutes.api.chatbot.dto;

import java.util.List;

public record ChatbotRequest(
    String message,
    List<ChatMessage> history
) {
  public record ChatMessage(
      String role, // "user" or "assistant"
      String content
  ) {}
}
