package com.helpinminutes.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional realtime event producer.
 *
 * <p>The former implementation published Redis only after commit. A process crash
 * in the small gap between the database commit and that callback permanently lost
 * the offer/status event. This producer writes an outbox row in the same database
 * transaction as the business change, attempts delivery immediately after commit,
 * and leaves the row for the retry worker if Redis and HTTP are unavailable.
 */
@Service
public class RealtimePublisher {
  private final RealtimeOutboxRepository outbox;
  private final RealtimeOutboxDispatcher dispatcher;
  private final ObjectMapper objectMapper;
  private final Executor executor;

  public RealtimePublisher(
      RealtimeOutboxRepository outbox,
      RealtimeOutboxDispatcher dispatcher,
      ObjectMapper objectMapper,
      @Qualifier("realtimeDispatchExecutor") Executor executor) {
    this.outbox = outbox;
    this.dispatcher = dispatcher;
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  public void publish(String type, Map<String, Object> payload) {
    if (type == null || type.isBlank() || payload == null) return;
    RealtimeOutboxEntity event = new RealtimeOutboxEntity();
    event.setId(UUID.randomUUID());
    event.setEventType(type);
    try {
      event.setPayloadJson(objectMapper.writeValueAsString(payload));
    } catch (Exception e) {
      throw new IllegalArgumentException("Realtime payload is not serializable", e);
    }
    Instant now = Instant.now();
    event.setStatus("PENDING");
    event.setAttempts(0);
    event.setNextAttemptAt(now);
    event.setCreatedAt(now);
    event.setUpdatedAt(now);
    outbox.save(event);

    Runnable deliver = () -> executor.execute(() -> dispatcher.dispatchOne(event.getId()));
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          deliver.run();
        }
      });
    } else {
      deliver.run();
    }
  }
}
