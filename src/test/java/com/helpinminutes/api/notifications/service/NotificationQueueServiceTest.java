package com.helpinminutes.api.notifications.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.notifications.outbox.NotificationOutboxDispatcher;
import com.helpinminutes.api.notifications.outbox.NotificationOutboxEntity;
import com.helpinminutes.api.notifications.outbox.NotificationOutboxRepository;
import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import com.helpinminutes.api.tasks.model.TaskEntity;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationQueueServiceTest {
  private NotificationOutboxRepository outbox;
  private NotificationOutboxDispatcher dispatcher;
  private ObjectMapper objectMapper;
  private NotificationQueueService queueService;

  @BeforeEach
  void setUp() {
    outbox = mock(NotificationOutboxRepository.class);
    dispatcher = mock(NotificationOutboxDispatcher.class);
    objectMapper = new ObjectMapper().findAndRegisterModules();
    when(outbox.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    queueService = new NotificationQueueService(outbox, dispatcher, objectMapper, Runnable::run);
  }

  @Test
  void enqueueMatchingDispatchPersistsTaskIdentity() throws Exception {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    ReflectionTestUtils.setField(task, "buyerId", buyerId);

    queueService.enqueueMatchingDispatch(task);

    ArgumentCaptor<NotificationOutboxEntity> captor =
        ArgumentCaptor.forClass(NotificationOutboxEntity.class);
    verify(outbox).save(captor.capture());
    NotificationJob job = objectMapper.readValue(captor.getValue().getJobJson(), NotificationJob.class);
    assertEquals(NotificationType.MATCHING_DISPATCH, job.type());
    assertEquals(taskId, job.taskId());
    assertEquals(buyerId, job.buyerId());
    assertEquals(0, job.dispatchWave());
    assertEquals(Boolean.TRUE, job.sendOfferNotifications());
    verify(dispatcher).dispatchOne(captor.getValue().getId());
  }

  @Test
  void enqueueMatchingDispatchPreservesOfferNotificationPolicy() throws Exception {
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(task, "buyerId", UUID.randomUUID());

    queueService.enqueueMatchingDispatch(task, false);

    ArgumentCaptor<NotificationOutboxEntity> captor =
        ArgumentCaptor.forClass(NotificationOutboxEntity.class);
    verify(outbox).save(captor.capture());
    NotificationJob job = objectMapper.readValue(captor.getValue().getJobJson(), NotificationJob.class);
    assertEquals(Boolean.FALSE, job.sendOfferNotifications());
  }
}
