package com.helpinminutes.api.matching;

import static com.helpinminutes.api.config.RabbitConfig.QUEUE_MATCHING_DISPATCH;

import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Dedicated worker pool for latency-sensitive first-wave matching. */
@Component
public class MatchingDispatchWorker {
  private static final Logger log = LoggerFactory.getLogger(MatchingDispatchWorker.class);
  private final TaskRepository tasks;
  private final MatchingService matching;
  private final MeterRegistry meters;

  public MatchingDispatchWorker(
      TaskRepository tasks, MatchingService matching, MeterRegistry meters) {
    this.tasks = tasks;
    this.matching = matching;
    this.meters = meters;
  }

  @RabbitListener(
      queues = QUEUE_MATCHING_DISPATCH,
      concurrency = "${MATCHING_LISTENER_CONCURRENCY:4-12}")
  public void handle(NotificationJob job) {
    if (job == null || job.type() != NotificationType.MATCHING_DISPATCH || job.taskId() == null) {
      throw new IllegalArgumentException("Invalid matching dispatch job");
    }
    var task = tasks.findById(job.taskId()).orElse(null);
    if (task == null) {
      // A deleted task is terminal, not a transient worker failure.
      log.info("Skipping matching dispatch for deleted task {}", job.taskId());
      return;
    }
    if (job.createdAt() != null) {
      meters.timer("matching.dispatch.queue.latency")
          .record(Duration.between(job.createdAt(), Instant.now()).abs());
    }
    Timer.Sample sample = Timer.start(meters);
    try {
      // dispatchOffers atomically compares the dispatch wave, making a broker
      // redelivery safe after an uncertain acknowledgement.
      var offered = matching.dispatchOffers(
          task,
          !Boolean.FALSE.equals(job.sendOfferNotifications()),
          job.dispatchWave());
      meters.counter(
          "matching.dispatch.result", "outcome", offered.isEmpty() ? "no_offer" : "offered")
          .increment();
    } catch (RuntimeException e) {
      meters.counter("matching.dispatch.result", "outcome", "failed").increment();
      throw e;
    } finally {
      sample.stop(meters.timer("matching.dispatch.duration"));
    }
  }
}
