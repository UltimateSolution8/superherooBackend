package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.common.SchedulerLock;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.payments.gateway.RazorpayXGateway;
import com.helpinminutes.api.payments.model.PayoutItemEntity;
import com.helpinminutes.api.payments.model.PayoutStatus;
import com.helpinminutes.api.payments.model.PayoutWebhookEventEntity;
import com.helpinminutes.api.payments.repo.PayoutItemRepository;
import com.helpinminutes.api.payments.repo.PayoutWebhookEventRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Payout webhooks.
 *
 * <p>Providers redeliver whenever they are unsure we received something, and a
 * replayed "reversed" that credits the balance a second time hands a partner money
 * nobody owes them. The event id is the primary key of a table, so the duplicate
 * loses at the database — before anything is applied.
 *
 * <p>And an unsigned body is not processed at all: this endpoint is necessarily
 * unauthenticated, so the signature is the only thing separating RazorpayX from
 * anybody who guessed the URL.
 */
class PayoutWebhookTest {

  private static final String REVERSED_BODY = """
      {"event":"payout.reversed","payload":{"payout":{"entity":{
        "id":"pout_ABC","status":"reversed","failure_reason":"Account closed"}}}}""";

  private final Set<String> seenEvents = new HashSet<>();
  private PayoutItemRepository items;
  private PayoutWebhookEventRepository events;
  private PayoutService payouts;
  private RazorpayXGateway razorpayx;
  private PayoutReconciliationJob job;
  private PayoutItemEntity item;

  @BeforeEach
  void setUp() {
    seenEvents.clear();

    item = new PayoutItemEntity();
    item.setId(UUID.randomUUID());
    item.setUserId(UUID.randomUUID());
    item.setAmountPaise(50_000L);
    item.setStatus(PayoutStatus.PROCESSING);
    item.setProviderPayoutId("pout_ABC");

    items = mock(PayoutItemRepository.class);
    when(items.findByProviderPayoutId("pout_ABC")).thenReturn(Optional.of(item));

    events = mock(PayoutWebhookEventRepository.class);
    when(events.saveAndFlush(any(PayoutWebhookEventEntity.class))).thenAnswer(invocation -> {
      PayoutWebhookEventEntity event = invocation.getArgument(0);
      if (!seenEvents.add(event.getEventId())) {
        throw new DataIntegrityViolationException("duplicate event id");
      }
      return event;
    });

    payouts = mock(PayoutService.class);
    razorpayx = mock(RazorpayXGateway.class);
    when(razorpayx.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);

    AppProperties props = mock(AppProperties.class);
    when(props.payments()).thenReturn(new AppProperties.Payments(false, true, true, 1500, 10_000L));

    SchedulerLock lock = mock(SchedulerLock.class);
    job = new PayoutReconciliationJob(
        items,
        events,
        payouts,
        razorpayx,
        lock,
        props,
        mock(com.helpinminutes.api.payments.repo.PayoutAccountValidationRepository.class),
        mock(PayoutAccountValidationService.class));
  }

  @Test
  void aReversalIsAppliedOnce() {
    job.handleWebhook(REVERSED_BODY, "sig", "evt_1");

    verify(payouts).applyProviderStatus(item, "reversed", null, "Account closed");
  }

  @Test
  void aRedeliveredEventIsDropped() {
    job.handleWebhook(REVERSED_BODY, "sig", "evt_1");
    job.handleWebhook(REVERSED_BODY, "sig", "evt_1");
    job.handleWebhook(REVERSED_BODY, "sig", "evt_1");

    verify(payouts, times(1)).applyProviderStatus(any(), anyString(), any(), any());
  }

  @Test
  void anUnsignedOrForgedBodyIsNotProcessed() {
    when(razorpayx.verifyWebhookSignature(anyString(), any())).thenReturn(false);

    job.handleWebhook(REVERSED_BODY, "not-the-real-signature", "evt_2");

    verify(payouts, never()).applyProviderStatus(any(), anyString(), any(), any());
    verify(events, never()).saveAndFlush(any());
  }

  @Test
  void aWebhookForAPayoutWeDoNotHaveIsIgnoredQuietly() {
    when(items.findByProviderPayoutId("pout_ABC")).thenReturn(Optional.empty());

    job.handleWebhook(REVERSED_BODY, "sig", "evt_3");

    verify(payouts, never()).applyProviderStatus(any(), anyString(), any(), any());
  }

  @Test
  void anEventWithNoIdStillDeduplicatesOnItsPayout() {
    // RazorpayX has omitted the header before. Falling back to event type plus payout
    // id keeps the dedup working rather than treating every redelivery as new.
    job.handleWebhook(REVERSED_BODY, "sig", null);
    job.handleWebhook(REVERSED_BODY, "sig", "");

    verify(payouts, times(1)).applyProviderStatus(any(), anyString(), any(), any());
    assertEquals(1, seenEvents.size());
  }

  @Test
  void malformedJsonDoesNotThrowIntoTheProvidersFace() {
    // A 500 makes RazorpayX retry forever. Log and absorb.
    job.handleWebhook("{ not json", "sig", "evt_4");

    verify(payouts, never()).applyProviderStatus(any(), anyString(), any(), any());
  }
}
