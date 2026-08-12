package com.helpinminutes.api.notifications.outbox;

import static com.helpinminutes.api.config.RabbitConfig.ROUTING_KEY_MATCHING_DISPATCH;
import static com.helpinminutes.api.config.RabbitConfig.ROUTING_KEY_NOTIFICATION_SEND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationOutboxDispatcherTest {
  @Test
  void latencySensitiveMatchingUsesItsDedicatedQueue() {
    NotificationJob job = NotificationJob.matchingDispatch(
        UUID.randomUUID(), UUID.randomUUID(), 3, true);
    assertEquals(ROUTING_KEY_MATCHING_DISPATCH, NotificationOutboxDispatcher.routingKeyFor(job));
  }

  @Test
  void pushJobsStayOnTheNotificationQueue() {
    NotificationJob job = NotificationJob.now(
        NotificationType.TASK_OFFERED,
        UUID.randomUUID(),
        UUID.randomUUID(),
        List.of(UUID.randomUUID()));
    assertEquals(ROUTING_KEY_NOTIFICATION_SEND, NotificationOutboxDispatcher.routingKeyFor(job));
  }
}
