package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.config.TestAppProperties;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.payments.service.PaymentLifecycleService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.RecurringTaskRepository;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskAcceptFlowTest {
  private TaskRepository tasks;
  private TaskOfferRepository offers;
  private UserRepository users;
  private HelperProfileRepository profiles;
  private RealtimePublisher realtime;
  private NotificationQueueService notifications;
  private PaymentLifecycleService payments;
  private TaskMapper mapper;
  private TaskService service;
  private UUID helperId;
  private UUID buyerId;
  private TaskEntity task;
  private HelperProfileEntity profile;

  @BeforeEach
  void setUp() {
    tasks = mock(TaskRepository.class);
    offers = mock(TaskOfferRepository.class);
    users = mock(UserRepository.class);
    profiles = mock(HelperProfileRepository.class);
    realtime = mock(RealtimePublisher.class);
    notifications = mock(NotificationQueueService.class);
    payments = mock(PaymentLifecycleService.class);
    mapper = mock(TaskMapper.class);
    service = new TaskService(
        tasks,
        offers,
        mock(MatchingService.class),
        realtime,
        mock(SupabaseStorageService.class),
        mock(HelperPresenceService.class),
        TestAppProperties.defaults(),
        users,
        profiles,
        notifications,
        mock(PushNotificationService.class),
        mapper,
        mock(RecurringTaskRepository.class),
        mock(TaskModerationService.class),
        mock(BookingBatchRepository.class),
        mock(BookingBatchItemRepository.class),
        new ObjectMapper(),
        mock(InvoiceEmailService.class),
        payments);

    helperId = UUID.randomUUID();
    buyerId = UUID.randomUUID();
    UserEntity helper = new UserEntity();
    helper.setId(helperId);
    helper.setRole(UserRole.HELPER);
    helper.setEmailVerified(true);
    profile = new HelperProfileEntity();
    profile.setUserId(helperId);
    profile.setKycStatus(HelperKycStatus.APPROVED);
    profile.setOffersSeen(4);
    profile.setOffersAccepted(1);
    task = new TaskEntity();
    task.setBuyerId(buyerId);
    task.setTitle("Urgent pickup");
    task.setDescription("Pick up medicine");
    task.setUrgency(TaskUrgency.HIGH);
    task.setTimeMinutes(20);
    task.setBudgetPaise(25_000L);
    task.setLat(17.385);
    task.setLng(78.4867);
    task.setStatus(TaskStatus.SEARCHING);
    task.prePersist();

    TaskOfferEntity offer = new TaskOfferEntity();
    offer.setTaskId(task.getId());
    offer.setHelperId(helperId);
    offer.setStatus(TaskOfferStatus.OFFERED);
    offer.setOfferedAt(Instant.now());
    offer.setExpiresAt(Instant.now().plusSeconds(60));

    when(users.findByIdForUpdate(helperId)).thenReturn(Optional.of(helper));
    when(profiles.findById(helperId)).thenReturn(Optional.of(profile));
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(tasks.existsByAssignedHelperIdAndStatusIn(eq(helperId), any())).thenReturn(false);
    when(offers.findByTaskIdAndHelperId(task.getId(), helperId)).thenReturn(Optional.of(offer));
    when(offers.respond(eq(task.getId()), eq(helperId), eq(TaskOfferStatus.OFFERED),
        eq(TaskOfferStatus.ACCEPTED), any())).thenReturn(1);
    when(mapper.toResponse(task, false)).thenReturn(mock(TaskResponse.class));
  }

  @Test
  void offeredPartnerAtomicallyWinsAndBothRealtimeAndPushAreEnqueued() {
    when(tasks.assignIfUnassigned(
        task.getId(), helperId, TaskStatus.SEARCHING, TaskStatus.ASSIGNED)).thenReturn(1);

    service.acceptTask(helperId, task.getId());

    assertEquals(TaskStatus.ASSIGNED, task.getStatus());
    assertEquals(helperId, task.getAssignedHelperId());
    assertEquals(2, profile.getOffersAccepted());
    verify(offers).expireOthers(
        task.getId(), TaskOfferStatus.OFFERED, TaskOfferStatus.EXPIRED, helperId);
    verify(realtime).publish(eq("task_assigned"), any());
    verify(notifications).enqueueTaskAccepted(buyerId, task);
    verify(payments).bindHelper(task.getId(), helperId);
  }

  @Test
  void losingAConcurrentAcceptNeverPublishesAssignment() {
    when(tasks.assignIfUnassigned(
        task.getId(), helperId, TaskStatus.SEARCHING, TaskStatus.ASSIGNED)).thenReturn(0);

    assertThrows(ConflictException.class, () -> service.acceptTask(helperId, task.getId()));

    verify(realtime, never()).publish(eq("task_assigned"), any());
    verify(notifications, never()).enqueueTaskAccepted(any(), any());
    verify(payments, never()).bindHelper(any(), any());
  }
}
