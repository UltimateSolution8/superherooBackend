package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.common.SchedulerLock;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskScheduleDispatchJob {
  private static final Logger log = LoggerFactory.getLogger(TaskScheduleDispatchJob.class);

  private final SchedulerLock schedulerLock;
  private final TaskRepository tasks;
  private final TaskOfferRepository offers;
  private final MatchingService matching;
  private final RealtimePublisher realtime;
  private final PushNotificationService pushNotifications;

  public TaskScheduleDispatchJob(
      SchedulerLock schedulerLock,
      TaskRepository tasks,
      TaskOfferRepository offers,
      MatchingService matching,
      RealtimePublisher realtime,
      PushNotificationService pushNotifications) {
    this.schedulerLock = schedulerLock;
    this.tasks = tasks;
    this.offers = offers;
    this.matching = matching;
    this.realtime = realtime;
    this.pushNotifications = pushNotifications;
  }

  @Scheduled(fixedDelayString = "${TASK_SCHEDULE_DISPATCH_MS:60000}")
  public void dispatchScheduledTasks() {
    // Serialised across instances like every other scheduled job. Two runs would
    // flip the same SCHEDULED_PENDING task to SEARCHING and dispatch it twice;
    // the row lock in dispatchOffers makes that benign but it doubles the
    // buyer's activation push and the Redis command spend.
    schedulerLock.runExclusively("tasks.schedule-dispatch", this::runDispatch);
  }

  /** Transaction is provided by SchedulerLock; see the note there. */
  public void runDispatch() {
    Instant now = Instant.now();
    List<TaskEntity> due = tasks.findTop50ByStatusAndScheduledAtBeforeAndAssignedHelperIdIsNullOrderByScheduledAtAsc(
        TaskStatus.SCHEDULED_PENDING,
        now.plus(java.time.Duration.ofMinutes(1)));

    if (due.isEmpty()) {
      return;
    }

    int dispatched = 0;
    for (TaskEntity task : due) {
      if (hasActiveOffer(task, now)) {
        continue;
      }
      try {
        // Starts a fresh search window: a scheduled task activating hours after
        // it was booked must not inherit a createdAt-based clock.
        task.beginSearching(now);
        tasks.save(task);

        try {
          realtime.publish(
              "task_status_changed",
              java.util.Map.of(
                  "taskId", task.getId().toString(),
                  "buyerId", task.getBuyerId().toString(),
                  "status", TaskStatus.SEARCHING.name()));
        } catch (Exception re) {
          log.warn("Failed to publish real-time status change for task {}", task.getId(), re);
        }
        try {
          pushNotifications.notifyBuyerScheduledTaskActivated(task.getBuyerId(), task);
        } catch (Exception pe) {
          log.warn("Failed to notify buyer for scheduled task activation {}", task.getId(), pe);
        }

        matching.dispatchOffers(task);
        dispatched++;
        try {
          pushNotifications.notifyBuyerScheduleSearchStarted(task.getBuyerId(), task);
        } catch (Exception ignored) {}
      } catch (Exception e) {
        log.error("Failed to dispatch scheduled task {}", task.getId(), e);
      }
    }

    if (dispatched > 0) {
      log.info("Dispatched {} scheduled tasks", dispatched);
    }
  }

  private boolean hasActiveOffer(TaskEntity task, Instant now) {
    try {
      return offers.findAllByTaskId(task.getId())
          .stream()
          .anyMatch(o -> o.getStatus() == TaskOfferStatus.OFFERED && o.getExpiresAt() != null && o.getExpiresAt().isAfter(now));
    } catch (Exception e) {
      log.warn("Failed to check active offers for task {}. Will attempt dispatch.", task.getId(), e);
      return false;
    }
  }
}
