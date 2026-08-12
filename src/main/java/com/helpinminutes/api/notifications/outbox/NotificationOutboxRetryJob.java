package com.helpinminutes.api.notifications.outbox;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxRetryJob {
  private static final Logger log = LoggerFactory.getLogger(NotificationOutboxRetryJob.class);
  private final NotificationOutboxRepository repository;
  private final NotificationOutboxDispatcher dispatcher;

  public NotificationOutboxRetryJob(
      NotificationOutboxRepository repository, NotificationOutboxDispatcher dispatcher) {
    this.repository = repository;
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${notification-outbox.poll-ms:1000}")
  public void retryDueEvents() {
    Instant now = Instant.now();
    repository.requeueStuck(now.minusSeconds(60), now);
    repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            "PENDING", now, PageRequest.of(0, 100))
        .forEach(event -> dispatcher.dispatchOne(event.getId()));
  }

  @Scheduled(cron = "${notification-outbox.retention-cron:0 30 4 * * *}", zone = "UTC")
  public void purgeOldEvents() {
    Instant now = Instant.now();
    int published = repository.deletePublishedBefore(now.minusSeconds(7L * 24 * 60 * 60));
    int dead = repository.deleteDeadBefore(now.minusSeconds(30L * 24 * 60 * 60));
    if (published > 0 || dead > 0) {
      log.info("Purged notification outbox rows published={} dead={}", published, dead);
    }
  }
}
