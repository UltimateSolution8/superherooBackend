package com.helpinminutes.api.notifications.outbox;

import static com.helpinminutes.api.config.RabbitConfig.EXCHANGE_NOTIFICATIONS;
import static com.helpinminutes.api.config.RabbitConfig.ROUTING_KEY_NOTIFICATION_SEND;
import static com.helpinminutes.api.config.RabbitConfig.ROUTING_KEY_MATCHING_DISPATCH;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.notifications.queue.NotificationJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationOutboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(NotificationOutboxDispatcher.class);
  // Keep broker-outage retries alive for roughly 24 hours at the 30-second cap.
  // Matching jobs live in this outbox, so an ordinary maintenance window must not
  // turn committed bookings into dead rows after only a few minutes.
  private static final int MAX_ATTEMPTS = 2_880;
  private final NotificationOutboxRepository repository;
  private final RabbitTemplate rabbit;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meters;

  public NotificationOutboxDispatcher(
      NotificationOutboxRepository repository,
      RabbitTemplate rabbit,
      ObjectMapper objectMapper,
      MeterRegistry meters) {
    this.repository = repository;
    this.rabbit = rabbit;
    this.objectMapper = objectMapper;
    this.meters = meters;
  }

  public void dispatchOne(UUID id) {
    Instant now = Instant.now();
    if (repository.claim(id, now) != 1) return;
    NotificationOutboxEntity event = repository.findById(id).orElse(null);
    if (event == null) return;
    try {
      NotificationJob job = objectMapper.readValue(event.getJobJson(), NotificationJob.class);
      String routingKey = routingKeyFor(job);
      // A dedicated channel with publisher confirms tells us the durable broker
      // accepted the message. Without this, convertAndSend returning only means it
      // was written to a socket buffer and the outbox could be marked too early.
      rabbit.invoke(operations -> {
        operations.convertAndSend(EXCHANGE_NOTIFICATIONS, routingKey, job);
        operations.waitForConfirmsOrDie(2_000);
        return null;
      });
      event.setStatus("PUBLISHED");
      event.setPublishedAt(Instant.now());
      event.setLockedAt(null);
      event.setLastError(null);
      event.setUpdatedAt(Instant.now());
      repository.save(event);
      meters.counter("notification.outbox", "result", "published").increment();
    } catch (Exception e) {
      int attempts = event.getAttempts() + 1;
      event.setAttempts(attempts);
      event.setLockedAt(null);
      event.setLastError(truncate(e.getMessage()));
      event.setUpdatedAt(Instant.now());
      if (attempts >= MAX_ATTEMPTS) {
        event.setStatus("DEAD");
        log.error("Notification outbox event {} exhausted {} attempts", id, attempts, e);
        meters.counter("notification.outbox", "result", "dead").increment();
      } else {
        event.setStatus("PENDING");
        long delayMs = Math.min(30_000L, 250L * (1L << Math.min(10, attempts - 1)));
        event.setNextAttemptAt(Instant.now().plusMillis(delayMs));
        meters.counter("notification.outbox", "result", "retry").increment();
      }
      repository.save(event);
    }
  }

  private static String truncate(String message) {
    if (message == null) return "unknown notification publish failure";
    return message.length() <= 500 ? message : message.substring(0, 500);
  }

  static String routingKeyFor(NotificationJob job) {
    return job != null
        && job.type() == com.helpinminutes.api.notifications.queue.NotificationType.MATCHING_DISPATCH
        ? ROUTING_KEY_MATCHING_DISPATCH
        : ROUTING_KEY_NOTIFICATION_SEND;
  }
}
