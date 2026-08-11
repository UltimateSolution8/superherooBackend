package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.tasks.service.moderation.ContentPolicy;
import com.helpinminutes.api.tasks.service.moderation.TextNormalizer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Local screening of task text, before any model call.
 *
 * <h2>Three outcomes, not two</h2>
 *
 * <pre>
 *   BLOCK    — matched {@link ContentPolicy} hard-illegal list. Decided here, no
 *              model call, no admin queue entry needed to reject it.
 *   ESCALATE — matched a context-sensitive term. The model decides, because only it
 *              can read context.
 *   CLEAN    — matched nothing. Approved here, no model call.
 * </pre>
 *
 * <p>CLEAN is the common case on an errands marketplace, and it is what makes
 * moderation nearly free: the model is only paid for the ambiguous minority.
 *
 * <h2>What was removed</h2>
 *
 * <p>A hand-trained Naive Bayes classifier used to make the final call on anything
 * the blocklist missed. It was fitted on 22 allowed and 27 prohibited sentences with
 * a bare {@code logP(prohibited) > logP(allowed)} comparison and no margin, so short
 * unfamiliar text like "fix the geyser" was decided by Laplace-smoothing noise. The
 * {@code isExempted} method existed only to unbreak the false positives it and the
 * blocklist produced. Both are gone; the model is the contextual judge now.
 *
 * <p>Also removed: the matched token used to be echoed back to the citizen
 * ("...restricted words: leak"), which told anyone probing the filter exactly which
 * term tripped it.
 */
@Service
public class TaskModerationService {

  private static final Logger log = LoggerFactory.getLogger(TaskModerationService.class);

  /** What local screening concluded. */
  public enum Verdict {
    CLEAN,
    ESCALATE,
    BLOCK
  }

  /**
   * @param reasons machine-readable codes for the audit trail and admin UI
   * @param contextTerms terms that triggered escalation; passed to the model so its
   *     prompt can stay short and still know what to look at
   * @param citizenMessage safe, non-specific wording for a blocked task
   */
  public record ScreeningResult(
      Verdict verdict,
      List<String> reasons,
      List<String> contextTerms,
      String citizenMessage) {

    public boolean isBlocked() {
      return verdict == Verdict.BLOCK;
    }

    public boolean needsModel() {
      return verdict == Verdict.ESCALATE;
    }
  }

  /**
   * Screens a task's text.
   *
   * <p>Pure and sub-millisecond: no I/O, no model, no database. Safe to call on the
   * request thread, which is why the fast paths can stay synchronous while only
   * escalations go async.
   */
  public ScreeningResult screen(String title, String description) {
    String combined = ((title == null ? "" : title) + " " + (description == null ? "" : description)).trim();
    if (combined.isEmpty()) {
      return new ScreeningResult(Verdict.CLEAN, List.of(), List.of(), null);
    }

    String normalized = ContentPolicy.applyTransliterations(TextNormalizer.normalize(combined));
    String deleeted = TextNormalizer.deleet(normalized);

    Optional<ContentPolicy.Hit> hardHit = ContentPolicy.findHardIllegal(normalized, deleeted);
    if (hardHit.isPresent()) {
      ContentPolicy.Hit hit = hardHit.get();
      // The matched text goes to the log and the audit trail, never to the citizen.
      log.info("Task blocked by policy: category={} statute={} match=\"{}\"",
          hit.category().code(), hit.category().statute(), hit.matchedText());
      return new ScreeningResult(
          Verdict.BLOCK,
          List.of("POLICY_" + hit.category().code()),
          List.of(),
          hit.category().citizenMessage());
    }

    List<String> contextTerms = new java.util.ArrayList<>(ContentPolicy.findContextTerms(normalized));
    List<String> reasons = new java.util.ArrayList<>();
    if (!contextTerms.isEmpty()) {
      reasons.add("NEEDS_CONTEXT");
    }
    // Off-platform contact details. Escalated, not blocked: sometimes the number
    // belongs to the shop the partner has to call, and only a reader can tell.
    if (ContentPolicy.hasContactDetails(normalized)) {
      reasons.add("CONTACT_DETAILS");
      contextTerms.add("contact details");
    }
    if (!reasons.isEmpty()) {
      return new ScreeningResult(
          Verdict.ESCALATE, List.copyOf(reasons), List.copyOf(contextTerms), null);
    }

    return new ScreeningResult(Verdict.CLEAN, List.of(), List.of(), null);
  }

  /**
   * Throws when the text is hard-blocked.
   *
   * <p>Retained for the bulk CSV path, which reports a per-row failure rather than
   * carrying a verdict. Note it deliberately does <em>not</em> throw on ESCALATE: a
   * row mentioning "medicine" is not a rejected row, it is one the model has to read.
   */
  public void validateTask(String title, String description) {
    ScreeningResult result = screen(title, description);
    if (result.isBlocked()) {
      throw new BadRequestException(result.citizenMessage());
    }
  }
}
