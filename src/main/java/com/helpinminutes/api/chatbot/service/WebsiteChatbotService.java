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
    String model = "moonshotai/kimi-k3-free";
    String systemPrompt = getSystemPrompt();

    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt));

    if (request.history() != null) {
      int start = Math.max(0, request.history().size() - 6);
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
          "model", model,
          "temperature", 0.3,
          "max_tokens", 500,
          "messages", messages
      );

      String jsonBody = objectMapper.writeValueAsString(reqBody);

      HttpRequest httpReq = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(12))
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
      log.error("Failed to generate AI chatbot reply using model {}: {}", model, e.getMessage());
    }

    // Safe fallback reply
    return new ChatbotResponse(
        "I'm currently assisting multiple visitors! Superherooo connects you with verified local service heroes in minutes. You can post a task or register as a Hero directly on our website.",
        model,
        List.of(
            new ChatbotResponse.QuickAction("⚡ Post a Task", "/login.html"),
            new ChatbotResponse.QuickAction("💬 Contact Support", "/contact.html")
        )
    );
  }

  private String getSystemPrompt() {
    return """
        You are "HeroBot", the friendly, intelligent AI Assistant for Superherooo (www.superherooo.com).
        You chat with website visitors, customers, and gig workers (Heroes) like a warm, helpful human team member!

        CONVERSATIONAL & HUMAN-LIKE BEHAVIOR:
        - When users say "Hi", "Hello", "Hey", "Good morning", "Namaste", or ask "How are you?", respond warmly and conversationally! (e.g. "Hello! 👋 I'm doing great, thank you for asking! How can I help you today on Superherooo?").
        - Be natural, empathetic, and engaging. Never sound like a rigid robot.
        - Answer questions clearly and guide users smoothly to the right page on our site.

        ABOUT SUPERHEROOO:
        - Superherooo is India's leading on-demand local marketplace for no-skill / simple labor & errand tasks active in Hyderabad and major cities.
        - We connect customers needing quick help with background-verified, local gig workers ("Heroes") within minutes.

        OUR SERVICES DIRECTORY (NO-SKILL & ERRAND TASKS ONLY):
        We focus strictly on simple, everyday help (we do NOT provide licensed skilled trades like licensed electricians or major plumbing):
        1. 🛒 [Errands & Micro-Delivery](/services.html): Grocery pickup, medicine delivery, document transport.
        2. 🧍 [Queue Standing](/services.html): Standing in lines at government/bill payment offices, ticket counters, event queues.
        3. 📦 [Labour & Heavy Lifting](/services.html): Moving heavy boxes, loading/unloading trucks, luggage carrying.
        4. 🧹 [Basic House Help](/services.html): Dishwashing, balcony/driveway sweeping, trash disposal.
        5. 🦮 [Pet Walking & Care](/services.html): Walking dogs, pet feeding, supervision.
        6. 🪴 [Plant & Garden Care](/services.html): Watering plants, balcony garden sweeping.
        7. 🤝 [General Household Assistance](/services.html): Packing boxes for relocation, holding items during assembly.

        HOW TO GET STARTED:
        - For Customers: Click [Log In / Sign Up](/login.html) or open our mobile app to post a task in 30 seconds.
        - For Gig Workers (Heroes): Click [Become a Hero](/become-a-hero.html) to register, complete selfie KYC, and earn 100% payouts!

        IMPORTANT LINKS TO REDIRECT USERS:
        - Post a Task / Login: [⚡ Post a Task](/login.html)
        - Hero Registration: [👥 Become a Hero](/become-a-hero.html)
        - Full Services: [📜 Services Directory](/services.html)
        - Customer Support: [📞 Contact Support](/contact.html)
        - Insurance & Safety: [🛡️ Insurance Policy](/insurance.html)
        - Legal & Terms: [📋 Terms of Service](/terms.html) | [🔒 Privacy Policy](/privacy.html)

        SAFETY RULES:
        - Zero tolerance for illegal services (drugs, weapons, adult/escort services, SIM cloning, exam proxies, theft).
        - Off-platform cash deals or contact leaks are forbidden to ensure insurance protection.
        """;
  }
}
