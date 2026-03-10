package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.matching.MatchingService;
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
import org.springframework.transaction.annotation.Transactional;

@Component
public class TaskScheduleDispatchJob {
  private static final Logger log = LoggerFactory.getLogger(TaskScheduleDispatchJob.class);

  private final TaskRepository tasks;
  private final TaskOfferRepository offers;
  private final MatchingService matching;

  public TaskScheduleDispatchJob(
      TaskRepository tasks,
      TaskOfferRepository offers,
      MatchingService matching) {
    this.tasks = tasks;
    this.offers = offers;
    this.matching = matching;
  }

  @Scheduled(fixedDelayString = "${TASK_SCHEDULE_DISPATCH_MS:60000}")
  @Transactional
  public void dispatchScheduledTasks() {
    Instant now = Instant.now();
    List<TaskEntity> due = tasks.findTop50ByStatusAndScheduledAtBeforeAndAssignedHelperIdIsNullOrderByScheduledAtAsc(
        TaskStatus.SEARCHING,
        now);

    if (due.isEmpty()) {
      return;
    }

    int dispatched = 0;
    for (TaskEntity task : due) {
      if (hasActiveOffer(task, now)) {
        continue;
      }
      try {
        matching.dispatchOffers(task);
        dispatched++;
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
