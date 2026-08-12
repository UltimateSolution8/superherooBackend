package com.helpinminutes.api.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RealtimeOutboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(RealtimeOutboxDispatcher.class);
  // At the 30-second backoff ceiling this keeps retrying for roughly 24 hours.
  // Twenty attempts died after about eight minutes, shorter than a routine Redis
  // maintenance window and unsafe for assignment/status events.
  private static final int MAX_ATTEMPTS = 2_880;
  private final RealtimeOutboxRepository repository;
  private final RealtimeTransport transport;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meters;

  public RealtimeOutboxDispatcher(
      RealtimeOutboxRepository repository,
      RealtimeTransport transport,
      ObjectMapper objectMapper,
      MeterRegistry meters) {
    this.repository = repository;
    this.transport = transport;
    this.objectMapper = objectMapper;
    this.meters = meters;
  }

  public void dispatchOne(UUID id) {
    Instant now = Instant.now();
    if (repository.claim(id, now) != 1) return;
    RealtimeOutboxEntity event = repository.findById(id).orElse(null);
    if (event == null) return;
    try {
      Map<String, Object> payload = objectMapper.readValue(
          event.getPayloadJson(), new TypeReference<>() {});
      if (!transport.deliver(event.getId().toString(), event.getEventType(), payload)) {
        throw new IllegalStateException("no realtime transport accepted the event");
      }
      event.setStatus("PUBLISHED");
      event.setPublishedAt(Instant.now());
      event.setLockedAt(null);
      event.setLastError(null);
      event.setUpdatedAt(Instant.now());
      repository.save(event);
      meters.counter("realtime.outbox", "result", "published").increment();
    } catch (Exception e) {
      int attempts = event.getAttempts() + 1;
      event.setAttempts(attempts);
      event.setLockedAt(null);
      event.setLastError(truncate(e.getMessage()));
      event.setUpdatedAt(Instant.now());
      if (attempts >= MAX_ATTEMPTS) {
        event.setStatus("DEAD");
        log.error("Realtime outbox event {} exhausted {} attempts", id, attempts, e);
        meters.counter("realtime.outbox", "result", "dead").increment();
      } else {
        event.setStatus("PENDING");
        long delayMs = Math.min(30_000L, 250L * (1L << Math.min(10, attempts - 1)));
        event.setNextAttemptAt(Instant.now().plusMillis(delayMs));
        meters.counter("realtime.outbox", "result", "retry").increment();
      }
      repository.save(event);
    }
  }

  private static String truncate(String message) {
    if (message == null) return "unknown delivery failure";
    return message.length() <= 500 ? message : message.substring(0, 500);
  }
}
