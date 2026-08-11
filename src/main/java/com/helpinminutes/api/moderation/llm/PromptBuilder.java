package com.helpinminutes.api.moderation.llm;

import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import org.springframework.stereotype.Component;

/**
 * Builds the moderation prompt.
 *
 * <p>Deliberately short. The previous version was a ~700-token, thirteen-criterion
 * rubric resent verbatim on every call, and two of its criteria could never fire
 * because the fields they read were always null or empty. It also forbade the model
 * from rejecting anything ("AI MUST NEVER REJECT TASKS DIRECTLY"), which meant every
 * genuinely illegal request still had to wait for a human.
 *
 * <p>What it does now:
 *
 * <ul>
 *   <li>Only sees tasks local screening could not decide, and is told which terms
 *       triggered that — so it can go straight to the question at hand.
 *   <li>May return BLOCK. Hard policy is caught locally, but the model catches the
 *       phrasings a list cannot.
 *   <li>Carries a short list of tricky-but-legal examples. This is the cheapest
 *       possible defence against false positives, which is what the previous
 *       pipeline actually suffered from.
 * </ul>
 */
@Component
public class PromptBuilder {

  public String buildSystemPrompt() {
    return """
        You moderate task requests on Superherooo, a marketplace for everyday errands \
        and household help in Hyderabad, India. Workers are ordinary people, not \
        licensed professionals.

        A local filter has already rejected clearly illegal requests and approved \
        clearly harmless ones. You only see borderline cases, so read for intent.

        BLOCK if the request is for: controlled substances (NDPS Act); firearms, \
        ammunition or explosives (Arms Act); sexual or companionship services (ITPA); \
        protected wildlife or restricted timber; prenatal sex determination (PCPNDT); \
        hawala or money laundering; forged documents or currency; exam impersonation; \
        unauthorised access to accounts or devices (IT Act); violence, threats or \
        intimidation; trafficking, bonded or child labour; sale of human organs; \
        home delivery of alcohol or tobacco (Telangana Excise Act).

        REVIEW if the request is lawful but risky or unclear: it moves contact or \
        payment off-platform, is too vague to price or complete safely, involves \
        handling large sums or valuables with no verification, asks an untrained \
        worker to do licensed work (medical, legal, electrical mains), involves an \
        unaccompanied minor or a vulnerable adult, or reads as a possible cover story.

        APPROVE otherwise. These are all legitimate and must be approved:
        - "Fix the leak under the kitchen sink" — plumbing, not a data leak
        - "Remove the weeds from my garden" — gardening, not cannabis
        - "Buy cough syrup" or "collect homeopathic medicines" — over-the-counter errand
        - "Accompany my mother to her doctor appointment" — escorting, not practising medicine
        - "Pick up documents from my lawyer's office" — a collection, not legal advice
        - "Sharpen my kitchen knife" / "buy wine glasses" / "buy root beer" — household goods
        - "Deposit this cheque at the bank" — a routine errand
        - "Book a gas cylinder refill" / "get passport size photos printed" — everyday tasks

        Mentioning a sensitive word is not a violation. Judge what is being asked for.

        Reply with JSON only, no markdown and no commentary:
        {"status":"APPROVE|REVIEW|BLOCK","confidence":0-100,"riskScore":0-100,\
        "qualityScore":0-100,"reasons":["short reason"],"flags":["CODE"],\
        "requiresAdminReview":true|false}

        confidence is how sure you are of the status. riskScore is how harmful the \
        request would be if fulfilled. Set requiresAdminReview true only for REVIEW.
        """;
  }

  public String buildUserPrompt(TaskModerationPayload payload) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("Title: ").append(nullSafe(payload.title())).append('\n');
    prompt.append("Description: ").append(nullSafe(payload.description())).append('\n');
    if (payload.budgetPaise() != null) {
      // Money is stored in paise; the model reads rupees like the citizen does. A
      // mismatch between price and request is one of the better fraud signals.
      prompt.append("Budget: ₹").append(payload.budgetPaise() / 100).append('\n');
    }
    if (payload.addressText() != null && !payload.addressText().isBlank()) {
      prompt.append("Address: ").append(payload.addressText()).append('\n');
    }
    // The terms that caused escalation. Naming them keeps the system prompt short and
    // points the model at the actual question instead of re-deriving it.
    if (payload.category() != null && !payload.category().isBlank()) {
      prompt.append("Terms that triggered this review: ").append(payload.category()).append('\n');
    }
    return prompt.toString();
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
