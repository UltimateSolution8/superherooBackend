package com.helpinminutes.api.notifications.service;

import com.helpinminutes.api.config.RabbitConfig;
import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import com.helpinminutes.api.tasks.model.TaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationQueueServiceTest {
    private RabbitTemplate rabbitTemplate;
    private PushNotificationService pushNotificationService;
    private com.helpinminutes.api.tasks.repo.TaskRepository taskRepository;
    private NotificationQueueService queueService;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        pushNotificationService = mock(PushNotificationService.class);
        taskRepository = mock(com.helpinminutes.api.tasks.repo.TaskRepository.class);
        queueService = new NotificationQueueService(rabbitTemplate, pushNotificationService, taskRepository);
    }

    @Test
    void enqueueTaskCreated_shouldPublishJob() {
        UUID taskId = UUID.randomUUID();
        UUID helper1 = UUID.randomUUID();
        UUID helper2 = UUID.randomUUID();

        TaskEntity task = new TaskEntity();
        ReflectionTestUtils.setField(task, "id", taskId);

        queueService.enqueueTaskCreated(List.of(helper1, helper2), task);

        ArgumentCaptor<NotificationJob> captor = ArgumentCaptor.forClass(NotificationJob.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE_NOTIFICATIONS), eq(RabbitConfig.ROUTING_KEY_NOTIFICATION_SEND), captor.capture());

        NotificationJob job = captor.getValue();
        assertNotNull(job);
        assertEquals(NotificationType.TASK_CREATED, job.type());
        assertEquals(taskId, job.taskId());
        assertNull(job.buyerId());
        assertNotNull(job.helperIds());
        assertTrue(job.helperIds().contains(helper1));
        assertTrue(job.helperIds().contains(helper2));
    }
}
