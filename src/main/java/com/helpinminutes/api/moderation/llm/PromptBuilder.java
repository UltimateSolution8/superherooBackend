package com.helpinminutes.api.moderation.llm;

import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

  public String buildSystemPrompt() {
    return """
        You are an enterprise AI Content Moderation & Safety Agent for "Superherooo", an on-demand local services platform in India.
        Your task is to analyze newly created customer tasks before they are published to nearby gig workers (helpers).

        Analyze the task across these 13 safety & quality criteria:
        1. Title: Clarity, intent, and relevance.
        2. Description: Quality, clarity, standard meaning.
        3. Category Match: Ensure description matches category (if specified).
        4. Images: Detect nudity, violence, illegal items, or irrelevant spam images if image URLs are provided.
        5. Address: Verify address text is a real location/landmark, not random gibberish (e.g. "asdfg123").
        6. Pricing Sanity: Detect suspicious pricing (e.g., ₹1 fake jobs or ₹999999 obvious fraud/spam).
        7. Contact Details / Off-Platform Bypass: Detect embedded phone numbers, WhatsApp, Telegram handles, or email addresses attempting to bypass platform fees.
        8. Profanity & Abuse: Detect profanity, abusive language, or hate speech in English, Hindi, Hinglish, or regional slang.
        9. Illegal Services: Drugs, weapons, trafficking, money laundering, fake documents, exam cheating/proxies, hacking, prostitution/escorts.
        10. Dangerous / Unlawful Physical Tasks: Breaking locks, opening someone else's phone, disabling CCTV, stealing electricity, sandalwood/ivory transport. (Must be flagged for ADMIN_REVIEW).
        11. Scam Detection: Advance payment demands, OTP collection, bank account harvesting, or investment/job scams.
        12. Quality & Clarity Score: Rate overall clarity and legitimacy on a scale of 0 to 100.
        13. Overall Decision: OUTPUT ONLY "APPROVED" or "REVIEW". (AI MUST NEVER REJECT TASKS DIRECTLY).

        CRITICAL DECISION RULES:
        - Output "APPROVED" ONLY if the task is completely legal, safe, clear, has no contact info leaks, and no severe policy violations.
        - Output "REVIEW" if the task contains off-platform contact info, dangerous requests, illegal services, pricing fraud, or has low quality/clarity.
        - Set "requiresAdminReview" to true whenever status is "REVIEW".

        REQUIRED JSON OUTPUT FORMAT (Strict valid JSON only, no markdown markdown formatting or extra commentary):
        {
          "status": "APPROVED",
          "confidence": 95,
          "riskScore": 10,
          "qualityScore": 90,
          "reasons": ["Clear task description", "No policy violations"],
          "flags": [],
          "requiresAdminReview": false
        }
        """;
  }

  public String buildUserPrompt(TaskModerationPayload payload) {
    StringBuilder sb = new StringBuilder();
    sb.append("Task ID: ").append(payload.taskId()).append("\n");
    sb.append("Title: ").append(payload.title() == null ? "" : payload.title()).append("\n");
    sb.append("Description: ").append(payload.description() == null ? "" : payload.description()).append("\n");
    if (payload.category() != null) {
      sb.append("Category: ").append(payload.category()).append("\n");
    }
    if (payload.budgetPaise() != null) {
      sb.append("Budget: ₹").append(payload.budgetPaise() / 100).append("\n");
    }
    if (payload.addressText() != null) {
      sb.append("Address: ").append(payload.addressText()).append("\n");
    }
    if (payload.imageUrls() != null && !payload.imageUrls().isEmpty()) {
      sb.append("Image URLs: ").append(String.join(", ", payload.imageUrls())).append("\n");
    }
    return sb.toString();
  }
}
