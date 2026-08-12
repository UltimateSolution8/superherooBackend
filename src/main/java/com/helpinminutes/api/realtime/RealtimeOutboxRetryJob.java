package com.helpinminutes.api.realtime;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RealtimeOutboxRetryJob {
  private static final Logger log = LoggerFactory.getLogger(RealtimeOutboxRetryJob.class);
  private final RealtimeOutboxRepository repository;
  private final RealtimeOutboxDispatcher dispatcher;

  public RealtimeOutboxRetryJob(
      RealtimeOutboxRepository repository, RealtimeOutboxDispatcher dispatcher) {
    this.repository = repository;
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${realtime-outbox.poll-ms:1000}")
  public void retryDueEvents() {
    Instant now = Instant.now();
    repository.requeueStuck(now.minusSeconds(60), now);
    repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            "PENDING", now, PageRequest.of(0, 100))
        .forEach(event -> dispatcher.dispatchOne(event.getId()));
  }

  /**
   * Keeps the durable delivery ledger useful without allowing a high-volume event
   * stream to grow the primary database forever. Dead rows stay longer for incident
   * investigation; published rows have already served their replay purpose.
   */
  @Scheduled(cron = "${realtime-outbox.retention-cron:0 15 4 * * *}", zone = "UTC")
  public void purgeOldEvents() {
    Instant now = Instant.now();
    int published = repository.deletePublishedBefore(now.minusSeconds(7L * 24 * 60 * 60));
    int dead = repository.deleteDeadBefore(now.minusSeconds(30L * 24 * 60 * 60));
    if (published > 0 || dead > 0) {
      log.info("Purged realtime outbox rows published={} dead={}", published, dead);
    }
  }
}
