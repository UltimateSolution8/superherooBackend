package com.helpinminutes.api.chatbot.controller;

import com.helpinminutes.api.chatbot.dto.ChatbotRequest;
import com.helpinminutes.api.chatbot.dto.ChatbotResponse;
import com.helpinminutes.api.chatbot.service.WebsiteChatbotService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/chatbot")
@CrossOrigin(origins = "*")
public class ChatbotController {

  private final WebsiteChatbotService chatbotService;

  public ChatbotController(WebsiteChatbotService chatbotService) {
    this.chatbotService = chatbotService;
  }

  @PostMapping("/chat")
  public ChatbotResponse chat(@RequestBody ChatbotRequest request) {
    if (request.message() == null || request.message().isBlank()) {
      throw new IllegalArgumentException("Message cannot be empty");
    }
    return chatbotService.generateReply(request);
  }
}
