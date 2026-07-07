package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.tasks.model.RecurringTaskEntity;
import com.helpinminutes.api.tasks.model.RecurringTaskStatus;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.RecurringTaskRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RecurringTaskLookaheadJob {
  private static final Logger log = LoggerFactory.getLogger(RecurringTaskLookaheadJob.class);

  private final RecurringTaskRepository recurringTasks;
  private final TaskRepository tasks;
  private final TaskService taskService;

  public RecurringTaskLookaheadJob(
      RecurringTaskRepository recurringTasks,
      TaskRepository tasks,
      TaskService taskService) {
    this.recurringTasks = recurringTasks;
    this.tasks = tasks;
    this.taskService = taskService;
  }

  @Scheduled(fixedDelayString = "${RECURRING_TASK_LOOKAHEAD_MS:21600000}") // runs every 6 hours
  @Transactional
  public void generateLookaheadTasks() {
    log.info("Starting lookahead generator job for recurring tasks");
    List<RecurringTaskEntity> activeConfigs = recurringTasks.findAllByStatus(RecurringTaskStatus.ACTIVE);
    Instant now = Instant.now();
    Instant lookaheadHorizon = now.plus(7, ChronoUnit.DAYS);

    int generatedCount = 0;
    for (RecurringTaskEntity rec : activeConfigs) {
      try {
        generatedCount += fillLookahead(rec, now, lookaheadHorizon);
      } catch (Exception e) {
        log.error("Failed to generate lookahead tasks for recurring task config {}", rec.getId(), e);
      }
    }
    if (generatedCount > 0) {
      log.info("Lookahead generator job finished. Generated {} tasks.", generatedCount);
    }
  }

  private int fillLookahead(RecurringTaskEntity rec, Instant now, Instant horizon) {
    List<ZonedDateTime> upcomingOccurrences = RecurrenceCalculator.nextNOccurrences(rec, now, 20);
    List<TaskEntity> existingTasks = tasks.findByRecurringTaskId(rec.getId());

    int count = 0;
    for (ZonedDateTime zdt : upcomingOccurrences) {
      Instant scheduledAt = zdt.toInstant();
      if (scheduledAt.isAfter(horizon)) {
        break; // beyond the 7 day lookahead window
      }

      boolean exists = existingTasks.stream()
          .anyMatch(t -> t.getScheduledAt() != null
              && Math.abs(t.getScheduledAt().toEpochMilli() - scheduledAt.toEpochMilli()) < 1000
              && t.getStatus() != TaskStatus.CANCELLED);

      if (!exists) {
        var createdIds = taskService.spawnOccurrence(rec.getId(), scheduledAt);
        count += createdIds.size();
      }
    }
    return count;
  }
}
