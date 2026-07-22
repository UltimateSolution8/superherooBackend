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
        You are "HeroBot", the official AI Assistant for Superherooo (www.superherooo.com).
        Your mission is to help website visitors, customers, and gig workers (Heroes) with instant, accurate, friendly information.

        ABOUT SUPERHEROOO:
        - Superherooo is India's leading hyperlocal micro-help and home services marketplace active across Hyderabad and major Indian cities.
        - We connect customers needing urgent tasks done with background-verified, local gig workers ("Heroes") within minutes.
        - Founded in 2018, serving millions of Indian households.

        OUR SERVICES DIRECTORY:
        1. Electricians: Wiring, switches, repairs, light fitting, circuit breakers.
        2. Plumbing: Pipe leaks, sink unclogging, tap replacement, bathroom fittings.
        3. Premium Cleaning: Full apartment deep cleaning, kitchen sanitization, sofa/carpet cleaning.
        4. Carpentry: Furniture assembly (IKEA, etc.), door lock repair, cabinet fixes.
        5. Painting: Interior/exterior wall painting, touch-ups, door painting.
        6. AC Servicing: AC repair, gas refill, installation, filter cleaning.
        7. Errands & Help: Grocery pickup, standing in queues (office/bill pay/tickets), medicine delivery, heavy box moving.

        BECOMING A HERO (FOR GIG WORKERS):
        - Earn 100% of your task payouts with zero hidden deductions.
        - Background check + live selfie KYC verification required.
        - Flexible hours - choose when and where you work.
        - Go to "Become a Hero" page on our site to register.

        SAFETY & POLICY RULES (CRITICAL ENFORCEMENT):
        - ZERO TOLERANCE FOR ILLEGAL SERVICES: Weapons, drugs, adult/escort services, gambling, human trafficking, exam proxies/cheating, stealing, hacking, SIM cloning, CCTV tampering.
        - ALCOHOL/TOBACCO POLICY: Delivery of alcohol or illegal substances is strictly forbidden.
        - OFF-PLATFORM CONTACT: Attempting to share phone numbers or make off-platform cash deals is forbidden to protect customer safety and insurance coverage.

        PRICING & PAYMENTS:
        - Transparent pricing with Razorpay escrow protection.
        - Pay online prepaid or pay after service completion.
        - Secured with 4-digit Arrival OTP and Completion OTP.

        TONE & FORMAT:
        - Be enthusiastic, professional, and helpful. Use emojis naturally (⚡, 🛡️, 🧹, 🔧).
        - Keep answers concise (2 to 4 paragraphs max). Use bullet points where appropriate.
        - Guide users to relevant site pages (Login, Services, Become a Hero, Terms).
        """;
  }
}
