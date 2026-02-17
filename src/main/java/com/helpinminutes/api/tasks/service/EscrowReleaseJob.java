package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.tasks.model.TaskEscrowStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EscrowReleaseJob {
  private final TaskRepository tasks;
  private final UserRepository users;

  public EscrowReleaseJob(TaskRepository tasks, UserRepository users) {
    this.tasks = tasks;
    this.users = users;
  }

  @Scheduled(fixedDelayString = "${ESCROW_RELEASE_POLL_MS:60000}")
  @Transactional
  public void releaseDueEscrow() {
    Instant now = Instant.now();
    var due = tasks.findTop50ByEscrowStatusAndEscrowReleaseAtBefore(TaskEscrowStatus.RELEASE_SCHEDULED, now);
    for (var task : due) {
      if (task.getAssignedHelperId() == null) {
        task.setEscrowStatus(TaskEscrowStatus.HELD);
        task.setEscrowReleaseAt(null);
        continue;
      }
      var helper = users.findById(task.getAssignedHelperId());
      if (helper.isEmpty()) {
        continue;
      }
      long amount = task.getEscrowAmountPaise() == null ? 0L : task.getEscrowAmountPaise();
      var h = helper.get();
      Long bal = h.getDemoBalancePaise();
      long current = bal == null ? 0L : bal;
      h.setDemoBalancePaise(current + amount);
      users.save(h);

      task.setEscrowStatus(TaskEscrowStatus.RELEASED);
      task.setEscrowReleasedAt(now);
      tasks.save(task);
    }
  }
}
