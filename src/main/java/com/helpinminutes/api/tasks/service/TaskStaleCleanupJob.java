package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskEscrowStatus;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TaskStaleCleanupJob {
  private static final Logger log = LoggerFactory.getLogger(TaskStaleCleanupJob.class);

  private final TaskRepository tasks;
  private final UserRepository users;
  private final Duration staleAssigned;
  private final Duration searchingTimeout;
  private final RealtimePublisher realtime;

  public TaskStaleCleanupJob(
      TaskRepository tasks,
      UserRepository users,
      RealtimePublisher realtime,
      @Value("${TASK_ASSIGNED_STALE_MINUTES:20}") long staleAssignedMinutes,
      @Value("${TASK_SEARCH_TIMEOUT_SECONDS:120}") long searchingTimeoutSeconds) {
    this.tasks = tasks;
    this.users = users;
    this.realtime = realtime;
    this.staleAssigned = Duration.ofMinutes(Math.max(5, staleAssignedMinutes));
    this.searchingTimeout = Duration.ofSeconds(Math.max(60, searchingTimeoutSeconds));
  }

  @Scheduled(fixedDelayString = "${TASK_STALE_CLEANUP_MS:30000}")
  @Transactional
  public void closeStaleAssignedTasks() {
    closeTimedOutSearchingTasks();

    Instant cutoff = Instant.now().minus(staleAssigned);
    List<TaskEntity> stale = tasks.findTop100ByStatusAndUpdatedAtBefore(TaskStatus.ASSIGNED, cutoff);
    if (stale.isEmpty()) return;
    int closed = 0;
    Instant now = Instant.now();
    for (TaskEntity task : stale) {
      task.setStatus(TaskStatus.CANCELLED);
      task.setCancelReason("Auto-cancelled: no arrival confirmation");
      task.setCancelledByRole("SYSTEM");
      task.setCancelledByUserId(null);
      task.setCancelledAt(now);
      refundEscrow(task, now);
      tasks.save(task);
      closed++;
    }
    log.info("Auto-cancelled {} assigned tasks older than {} minutes", closed, staleAssigned.toMinutes());
  }

  private void closeTimedOutSearchingTasks() {
    Instant now = Instant.now();
    Instant cutoff = now.minus(searchingTimeout);
    List<TaskEntity> stale = tasks.findTimedOutSearchingTasks(TaskStatus.SEARCHING, cutoff, PageRequest.of(0, 200));
    if (stale.isEmpty()) return;
    int closed = 0;
    for (TaskEntity task : stale) {
      if (task.getAssignedHelperId() != null) {
        continue;
      }
      task.setStatus(TaskStatus.CANCELLED);
      task.setCancelReason("No helper accepted your task in time. Please try again.");
      task.setCancelledByRole("SYSTEM");
      task.setCancelledByUserId(null);
      task.setCancelledAt(now);
      refundEscrow(task, now);
      tasks.save(task);
      closed++;
      try {
        realtime.publish(
            "task_status_changed",
            java.util.Map.of(
                "taskId", task.getId().toString(),
                "buyerId", task.getBuyerId().toString(),
                "status", TaskStatus.CANCELLED.name()));
      } catch (Exception e) {
        log.warn("Could not publish timeout cancellation for task {}", task.getId(), e);
      }
    }
    if (closed > 0) {
      log.info("Auto-cancelled {} searching tasks after {} seconds timeout", closed, searchingTimeout.toSeconds());
    }
  }

  private void refundEscrow(TaskEntity task, Instant now) {
    if (task.getEscrowAmountPaise() == null || task.getEscrowAmountPaise() <= 0) return;
    if (task.getEscrowStatus() != TaskEscrowStatus.HELD && task.getEscrowStatus() != TaskEscrowStatus.RELEASE_SCHEDULED) {
      return;
    }
    UserEntity buyer = users.findById(task.getBuyerId()).orElse(null);
    if (buyer != null) {
      long current = buyer.getDemoBalancePaise() == null ? 0L : buyer.getDemoBalancePaise();
      buyer.setDemoBalancePaise(current + task.getEscrowAmountPaise());
      users.save(buyer);
    }
    task.setEscrowStatus(TaskEscrowStatus.REFUNDED);
    task.setEscrowReleaseAt(null);
    task.setEscrowReleasedAt(now);
    task.setEscrowReleasedToHelperId(null);
  }
}
