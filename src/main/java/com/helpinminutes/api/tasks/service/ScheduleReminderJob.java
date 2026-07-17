package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduleReminderJob {
  private static final Logger log = LoggerFactory.getLogger(ScheduleReminderJob.class);

  private final TaskRepository tasks;
  private final PushNotificationService pushNotifications;
  private final Set<String> sentReminders = ConcurrentHashMap.newKeySet();

  public ScheduleReminderJob(TaskRepository tasks, PushNotificationService pushNotifications) {
    this.tasks = tasks;
    this.pushNotifications = pushNotifications;
  }

  @Scheduled(fixedDelay = 300000)
  public void sendScheduleReminders() {
    Instant now = Instant.now();

    // Clear the set when it exceeds 500 entries to prevent memory leak
    if (sentReminders.size() > 500) {
      sentReminders.clear();
    }

    // Find SCHEDULED_PENDING tasks within the next 35 minutes
    List<TaskEntity> upcoming = tasks.findTop50ByStatusAndScheduledAtBeforeAndAssignedHelperIdIsNullOrderByScheduledAtAsc(
        TaskStatus.SCHEDULED_PENDING,
        now.plus(Duration.ofMinutes(35)));

    if (upcoming.isEmpty()) {
      return;
    }

    int sent = 0;
    for (TaskEntity task : upcoming) {
      if (task.getScheduledAt() == null || task.getBuyerId() == null) continue;

      long minutesUntil = Duration.between(now, task.getScheduledAt()).toMinutes();

      // 30-min reminder (25-35 min window)
      if (minutesUntil >= 25 && minutesUntil <= 35) {
        String key30 = task.getId() + ":30";
        if (sentReminders.add(key30)) {
          try {
            pushNotifications.notifyBuyerScheduleReminder(task.getBuyerId(), task, 30);
            sent++;
          } catch (Exception ignored) {}
        }
      }

      // 15-min reminder (10-20 min window)
      if (minutesUntil >= 10 && minutesUntil <= 20) {
        String key15 = task.getId() + ":15";
        if (sentReminders.add(key15)) {
          try {
            pushNotifications.notifyBuyerScheduleReminder(task.getBuyerId(), task, 15);
            sent++;
          } catch (Exception ignored) {}
        }
      }
    }

    if (sent > 0) {
      log.info("Sent {} schedule reminder notification(s)", sent);
    }
  }
}
