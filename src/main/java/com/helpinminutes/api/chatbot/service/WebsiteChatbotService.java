package com.helpinminutes.api.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.chatbot.dto.ChatbotRequest;
import com.helpinminutes.api.chatbot.dto.ChatbotResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WebsiteChatbotService {

  private static final Logger log = LoggerFactory.getLogger(WebsiteChatbotService.class);

  @Value("${ai.moderation.zenmux.api-key:sk-ai-v1-d399b9ba9d811b555ff0679b99e57255a03f1bd218590eea87d342270f82451e}")
  private String apiKey;

  @Value("${ai.moderation.zenmux.endpoint:https://zenmux.ai/api/v1/chat/completions}")
  private String endpoint;

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public WebsiteChatbotService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public ChatbotResponse generateReply(ChatbotRequest request) {
    String model = "superherooo-ai";
    String systemPrompt = getSystemPrompt();

    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt));

    if (request.history() != null) {
      int start = Math.max(0, request.history().size() - 10);
      for (int i = start; i < request.history().size(); i++) {
        var h = request.history().get(i);
        if (h.content() != null && !h.content().isBlank()) {
          messages.add(Map.of("role", h.role(), "content", h.content()));
        }
      }
    }

    messages.add(Map.of("role", "user", "content", request.message()));

    try {
      Map<String, Object> reqBody = Map.of(
          "model", "moonshotai/kimi-k3-free",
          "temperature", 0.4,
          "max_tokens", 600,
          "messages", messages
      );

      String jsonBody = objectMapper.writeValueAsString(reqBody);

      HttpRequest httpReq = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(15))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
          .build();

      HttpResponse<String> response = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        JsonNode root = objectMapper.readTree(response.body());
        String reply = root.path("choices").get(0).path("message").path("content").asText();

        List<ChatbotResponse.QuickAction> actions = List.of(
            new ChatbotResponse.QuickAction("⚡ Post a Task", "/login.html"),
            new ChatbotResponse.QuickAction("👥 Become a Hero", "/become-a-hero.html"),
            new ChatbotResponse.QuickAction("📜 View Services", "/services.html"),
            new ChatbotResponse.QuickAction("💬 Contact Support", "/contact.html")
        );

        return new ChatbotResponse(reply, model, actions);
      }
    } catch (Exception e) {
      log.error("Failed to generate AI chatbot reply using Superherooo AI: {}", e.getMessage());
    }

    // Safe fallback reply
    return new ChatbotResponse(
        "I am Superherooo AI! ⚡ Connecting you with verified local service heroes in minutes. You can post a task or register as a Hero directly on our website.",
        model,
        List.of(
            new ChatbotResponse.QuickAction("⚡ Post a Task", "/login.html"),
            new ChatbotResponse.QuickAction("💬 Contact Support", "/contact.html")
        )
    );
  }

  private String getSystemPrompt() {
    return """
        You are "Superherooo AI", the warm, highly intelligent, and conversational AI Assistant for Superherooo (www.superherooo.com).
        You engage website visitors, customers, and gig workers (Heroes) with context-aware, helpful, and natural conversation like an expert human support consultant!

        CONVERSATIONAL & MULTI-TURN CONTEXT RULES:
        - Maintain conversation context across messages. If the user previously mentioned a location (e.g. Hyderabad), service preference, or role (Customer/Hero), reference it naturally in follow-up answers!
        - When greeted with "Hi", "Hello", "Hey", "Good morning", "Namaste", or "How are you?", reply with genuine warmth (e.g. "Hello! 👋 I'm doing great, thank you for asking! How can Superherooo AI help you today?").
        - Keep answers concise, clear, and action-oriented with markdown links (`[Link Text](/page.html)`).
        - Always identify yourself strictly as "Superherooo AI".

        ABOUT SUPERHEROOO:
        - India's premier on-demand local services platform operating in Hyderabad & major metros.
        - Connects users with background-verified, local gig workers ("Heroes") within 15-30 minutes.

        SERVICE CATEGORIES (NO-SKILL & ERRAND TASKS ONLY):
        1. 🛒 [Errands & Micro-Delivery](/services.html): Grocery pickup, medicine delivery, document transport.
        2. 🧍 [Queue Standing](/services.html): Standing in lines at offices, counters, banks, event queues.
        3. 📦 [Labour & Heavy Lifting](/services.html): Moving heavy boxes, loading/unloading trucks, luggage assistance.
        4. 🧹 [Basic House Help](/services.html): Dishwashing, balcony/driveway sweeping, trash disposal.
        5. 🦮 [Pet Walking & Care](/services.html): Walking dogs, pet feeding.
        6. 🪴 [Plant & Garden Care](/services.html): Watering plants, balcony garden cleanup.
        7. 🤝 [General Household Help](/services.html): Packing boxes for relocation, holding items.

        QUICK NAVIGATION LINKS:
        - Post a Task: [⚡ Post a Task](/login.html)
        - Hero Registration: [👥 Become a Hero](/become-a-hero.html)
        - Full Services: [📜 Services Directory](/services.html)
        - Support: [📞 Contact Support](/contact.html)
        - Safety & Insurance: [🛡️ Insurance Policy](/insurance.html)
        - Legal: [📋 Terms](/terms.html) | [🔒 Privacy](/privacy.html)
        """;
  }
}
