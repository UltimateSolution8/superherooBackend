package com.helpinminutes.api.chatbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.chatbot.dto.ChatbotRequest;
import com.helpinminutes.api.chatbot.dto.ChatbotResponse;
import com.helpinminutes.api.chatbot.service.WebsiteChatbotService;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebsiteChatbotServiceTest {

  private WebsiteChatbotService service;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper();
    service = new WebsiteChatbotService(mapper);
  }

  @Test
  void generatesFallbackResponseWhenApiUnreachable() {
    ChatbotRequest request = new ChatbotRequest(
        "How do I book an electrician?",
        Collections.emptyList()
    );

    ChatbotResponse response = service.generateReply(request);
    assertNotNull(response);
    assertNotNull(response.reply());
    assertEquals("superherooo-ai", response.model());
    assertFalse(response.suggestedActions().isEmpty());
  }
}
