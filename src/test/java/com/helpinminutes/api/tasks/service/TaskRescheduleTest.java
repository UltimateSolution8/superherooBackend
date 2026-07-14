package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.repo.RecurringTaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.config.AppProperties;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class TaskRescheduleTest {

  private TaskRepository taskRepository;
  private TaskMapper taskMapper;
  private RealtimePublisher realtime;
  private TaskService taskService;

  @BeforeEach
  public void setUp() {
    taskRepository = mock(TaskRepository.class);
    taskMapper = mock(TaskMapper.class);
    realtime = mock(RealtimePublisher.class);

    taskService = new TaskService(
        taskRepository,
        mock(TaskOfferRepository.class),
        mock(MatchingService.class),
        realtime,
        mock(SupabaseStorageService.class),
        mock(HelperPresenceService.class),
        mock(AppProperties.class),
        mock(UserRepository.class),
        mock(HelperProfileRepository.class),
        mock(NotificationQueueService.class),
        mock(PushNotificationService.class),
        taskMapper,
        mock(RecurringTaskRepository.class),
        mock(TaskModerationService.class),
        mock(com.helpinminutes.api.batches.repo.BookingBatchRepository.class),
        mock(com.helpinminutes.api.batches.repo.BookingBatchItemRepository.class),
        mock(com.fasterxml.jackson.databind.ObjectMapper.class),
        mock(InvoiceEmailService.class)
    );
  }

  @Test
  public void testRescheduleSuccess() {
    UUID buyerId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);
    task.setStatus(TaskStatus.SCHEDULED_PENDING);

    Instant newTime = Instant.now().plus(1, ChronoUnit.HOURS);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    TaskResponse mockResponse = mock(TaskResponse.class);
    when(taskMapper.toResponse(eq(task), anyBoolean())).thenReturn(mockResponse);

    TaskResponse resp = taskService.rescheduleTask(buyerId, taskId, newTime);

    assertNotNull(resp);
    assertEquals(TaskStatus.SCHEDULED_PENDING, task.getStatus());
    assertEquals(newTime, task.getScheduledAt());
    verify(taskRepository, times(1)).save(task);
  }

  @Test
  public void testRescheduleNotOwner() {
    UUID buyerId = UUID.randomUUID();
    UUID otherBuyerId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);
    task.setStatus(TaskStatus.SCHEDULED_PENDING);

    Instant newTime = Instant.now().plus(1, ChronoUnit.HOURS);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

    assertThrows(ForbiddenException.class, () -> taskService.rescheduleTask(otherBuyerId, taskId, newTime));
  }

  @Test
  public void testRescheduleInvalidStatus() {
    UUID buyerId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);
    task.setStatus(TaskStatus.STARTED);

    Instant newTime = Instant.now().plus(1, ChronoUnit.HOURS);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

    assertThrows(BadRequestException.class, () -> taskService.rescheduleTask(buyerId, taskId, newTime));
  }

  @Test
  public void testRescheduleTimeInPast() {
    UUID buyerId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);
    task.setStatus(TaskStatus.SCHEDULED_PENDING);

    Instant newTime = Instant.now().minus(10, ChronoUnit.MINUTES);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

    assertThrows(BadRequestException.class, () -> taskService.rescheduleTask(buyerId, taskId, newTime));
  }
}
