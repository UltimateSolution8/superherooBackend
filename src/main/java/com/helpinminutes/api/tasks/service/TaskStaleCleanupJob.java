package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskEscrowStatus;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

  public TaskStaleCleanupJob(
      TaskRepository tasks,
      UserRepository users,
      @Value("${TASK_ASSIGNED_STALE_MINUTES:20}") long staleAssignedMinutes) {
    this.tasks = tasks;
    this.users = users;
    this.staleAssigned = Duration.ofMinutes(Math.max(5, staleAssignedMinutes));
  }

  @Scheduled(fixedDelayString = "${TASK_STALE_CLEANUP_MS:120000}")
  @Transactional
  public void closeStaleAssignedTasks() {
    Instant cutoff = Instant.now().minus(staleAssigned);
    List<TaskEntity> stale = tasks.findTop100ByStatusAndUpdatedAtBefore(TaskStatus.ASSIGNED, cutoff);
    if (stale.isEmpty()) return;
    int closed = 0;
    for (TaskEntity task : stale) {
      task.setStatus(TaskStatus.CANCELLED);
      task.setCancelReason("Auto-cancelled: no arrival confirmation");
      task.setCancelledByRole("SYSTEM");
      task.setCancelledByUserId(null);
      task.setCancelledAt(Instant.now());

      if (task.getEscrowAmountPaise() != null && task.getEscrowAmountPaise() > 0) {
        if (task.getEscrowStatus() == TaskEscrowStatus.HELD || task.getEscrowStatus() == TaskEscrowStatus.RELEASE_SCHEDULED) {
          UserEntity buyer = users.findById(task.getBuyerId()).orElse(null);
          if (buyer != null) {
            long current = buyer.getDemoBalancePaise() == null ? 0L : buyer.getDemoBalancePaise();
            buyer.setDemoBalancePaise(current + task.getEscrowAmountPaise());
            users.save(buyer);
          }
          task.setEscrowStatus(TaskEscrowStatus.REFUNDED);
          task.setEscrowReleaseAt(null);
          task.setEscrowReleasedAt(Instant.now());
          task.setEscrowReleasedToHelperId(null);
        }
      }
      tasks.save(task);
      closed++;
    }
    log.info("Auto-cancelled {} assigned tasks older than {} minutes", closed, staleAssigned.toMinutes());
  }
}
