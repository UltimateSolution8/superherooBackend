package com.helpinminutes.api.payments.service;

import com.helpinminutes.api.common.SchedulerLock;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.payments.gateway.RazorpayXGateway;
import com.helpinminutes.api.payments.model.PayoutItemEntity;
import com.helpinminutes.api.payments.model.PayoutStatus;
import com.helpinminutes.api.payments.model.PayoutWebhookEventEntity;
import com.helpinminutes.api.payments.repo.PayoutItemRepository;
import com.helpinminutes.api.payments.repo.PayoutWebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes the loop on payouts that neither succeeded nor failed in front of us.
 *
 * <p>Two ways a payout resolves: RazorpayX sends a webhook, or we ask. Both are
 * needed. Webhooks are lost and redelivered; polling alone would be slow and would
 * hammer the API. The webhook is the fast path, this job is the guarantee.
 *
 * <p>Modelled on {@link PaymentLifecycleService}'s refund retry loop — the same
 * {@link SchedulerLock} so only one instance runs it, and one transaction per item so
 * a single provider failure cannot block the rest of the batch.
 */
@Service
public class PayoutReconciliationJob {
  private static final Logger log = LoggerFactory.getLogger(PayoutReconciliationJob.class);

  /**
   * How long a payout may sit unresolved before we ask about it.
   *
   * <p>IMPS usually settles in under a minute; asking immediately would mostly
   * return "processing" and spend the rate limit on it.
   */
  private static final Duration SETTLE_GRACE = Duration.ofMinutes(2);

  private static final int BATCH_SIZE = 50;

  private final PayoutItemRepository items;
  private final PayoutWebhookEventRepository webhookEvents;
  private final PayoutService payouts;
  private final RazorpayXGateway razorpayx;
  private final SchedulerLock schedulerLock;
  private final AppProperties props;
  private final com.helpinminutes.api.payments.repo.PayoutAccountValidationRepository validations;
  private final PayoutAccountValidationService validationService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * This bean, through its own proxy.
   *
   * {@code reconcileOne} is {@code @Transactional} and is called from the loop
   * below — an ordinary {@code this.} call would go straight to the method and skip
   * the proxy, so no transaction would be started. The ledger writes it reaches are
   * {@code MANDATORY} and would then throw. Self-injection is the standard way out;
   * annotating the loop instead would put the whole batch in one transaction, and
   * one unreachable payout would roll back the others.
   */
  @org.springframework.beans.factory.annotation.Autowired
  @org.springframework.context.annotation.Lazy
  private PayoutReconciliationJob self;

  public PayoutReconciliationJob(
      PayoutItemRepository items,
      PayoutWebhookEventRepository webhookEvents,
      PayoutService payouts,
      RazorpayXGateway razorpayx,
      SchedulerLock schedulerLock,
      AppProperties props,
      com.helpinminutes.api.payments.repo.PayoutAccountValidationRepository validations,
      PayoutAccountValidationService validationService) {
    this.items = items;
    this.webhookEvents = webhookEvents;
    this.payouts = payouts;
    this.razorpayx = razorpayx;
    this.schedulerLock = schedulerLock;
    this.props = props;
    this.validations = validations;
    this.validationService = validationService;
  }

  @Scheduled(fixedDelayString = "${PAYOUT_RECONCILE_MS:60000}")
  public void reconcile() {
    if (!props.payments().payoutsEnabled() || !razorpayx.isConfigured()) return;
    schedulerLock.runExclusively("payments.payout-reconcile", this::reconcileLocked);
  }

  private void reconcileLocked() {
    List<PayoutItemEntity> open = items.findByStatusInAndRequestedAtBefore(
        List.of(PayoutStatus.PENDING, PayoutStatus.PROCESSING),
        Instant.now().minus(SETTLE_GRACE),
        PageRequest.of(0, BATCH_SIZE));

    for (PayoutItemEntity item : open) {
      try {
        self.reconcileOne(item.getId());
      } catch (Exception e) {
        // One unreachable payout must not stop the rest of the batch.
        log.warn("Payout {} could not be reconciled: {}", item.getId(), e.getMessage());
      }
    }
  }

  /**
   * Closes the loop on penny drops the same way.
   *
   * <p>Deliberately not gated on {@code payoutsEnabled}: accounts should be
   * verified <em>before</em> payouts go live, so that switching them on does not
   * begin with every partner unable to withdraw.
   */
  @Scheduled(fixedDelayString = "${PAYOUT_VALIDATION_POLL_MS:120000}")
  public void pollValidations() {
    if (!razorpayx.isConfigured()) return;
    schedulerLock.runExclusively("payments.validation-poll", this::pollValidationsLocked);
  }

  private void pollValidationsLocked() {
    for (com.helpinminutes.api.payments.model.PayoutAccountValidationEntity validation :
        validations.findPending()) {
      try {
        RazorpayXGateway.FundAccountValidationResult result =
            razorpayx.fetchFundAccountValidation(validation.getProviderValidationId());
        validationService.applyProviderResult(validation, result);
      } catch (Exception e) {
        // One unreachable validation must not stop the rest of the batch.
        log.warn("Validation {} could not be polled: {}", validation.getId(), e.getMessage());
      }
    }
  }

  @Transactional
  public void reconcileOne(java.util.UUID itemId) {
    PayoutItemEntity item = items.findById(itemId).orElse(null);
    if (item == null || item.getStatus().isTerminal()) return;

    if (item.getProviderPayoutId() == null) {
      // Submitted but we never learned its id — the response was lost. The
      // idempotency key means resending cannot create a second payout, so this is
      // safe, and it is the only way to recover the id.
      log.info("Payout {} has no provider id; leaving it for a manual check", item.getId());
      return;
    }

    RazorpayXGateway.PayoutResult result = razorpayx.fetchPayout(item.getProviderPayoutId());
    payouts.applyProviderStatus(item, result.status(), result.utr(), result.failureReason());
    items.save(item);
  }

  /**
   * Handles a RazorpayX payout webhook.
   *
   * <p>The signature is checked before the body is parsed, and the event id is
   * inserted before anything is applied: a redelivered {@code payout.reversed} that
   * credited the balance twice would hand the partner money nobody owes them.
   */
  @Transactional
  public void handleWebhook(String rawBody, String signature, String eventId) {
    if (!razorpayx.verifyWebhookSignature(rawBody, signature)) {
      log.warn("Rejected a RazorpayX webhook with an invalid signature");
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(rawBody);
      String eventType = root.path("event").asText("");
      String entityId =
          eventType.startsWith("fund_account.validation")
              ? root.path("payload").path("fund_account.validation").path("entity").path("id").asText("")
              : root.path("payload").path("payout").path("entity").path("id").asText("");
      String id = eventId == null || eventId.isBlank() ? eventType + ":" + entityId : eventId;

      PayoutWebhookEventEntity seen = new PayoutWebhookEventEntity();
      seen.setEventId(id);
      seen.setEventType(eventType);
      try {
        webhookEvents.saveAndFlush(seen);
      } catch (DataIntegrityViolationException duplicate) {
        log.debug("Ignoring a redelivered RazorpayX webhook {}", id);
        return;
      }

      if (eventType.startsWith("fund_account.validation")) {
        applyValidationWebhook(root);
        return;
      }

      JsonNode payout = root.path("payload").path("payout").path("entity");
      String providerPayoutId = payout.path("id").asText(null);
      if (providerPayoutId == null) return;

      items.findByProviderPayoutId(providerPayoutId).ifPresent(item -> {
        payouts.applyProviderStatus(
            item,
            payout.path("status").asText(null),
            payout.path("utr").asText(null),
            payout.path("failure_reason").asText(null));
        items.save(item);
      });
    } catch (Exception e) {
      log.error("Failed to process a RazorpayX webhook: {}", e.getMessage());
    }
  }

  private void applyValidationWebhook(JsonNode root) {
    JsonNode entity = root.path("payload").path("fund_account.validation").path("entity");
    String providerValidationId = entity.path("id").asText(null);
    if (providerValidationId == null || providerValidationId.isBlank()) return;

    validations
        .findByProviderValidationId(providerValidationId)
        .ifPresent(
            validation ->
                validationService.applyProviderResult(
                    validation,
                    new RazorpayXGateway.FundAccountValidationResult(
                        providerValidationId,
                        entity.path("status").asText(null),
                        entity.path("results").path("registered_name").asText(null),
                        entity.path("utr").asText(null),
                        entity.path("amount").asLong(0L),
                        entity.path("error_description").asText(null))));
  }
}
