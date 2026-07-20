package com.helpinminutes.api.mediator.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.helpinminutes.api.config.AppProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.batches.model.BookingBatchEntity;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.mediator.dto.MediatorDtos.*;
import com.helpinminutes.api.mediator.model.MediatorJobWorkerEntity;
import com.helpinminutes.api.mediator.repo.HelperMediatorLinkRepository;
import com.helpinminutes.api.mediator.repo.MediatorJobWorkerRepository;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.payments.repo.PaymentRepository;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.service.TaskService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MediatorServiceTest {
  private BookingBatchRepository batches;
  private MediatorJobWorkerRepository workers;
  private HelperMediatorLinkRepository helperMediatorLinks;
  private UserRepository users;
  private HelperProfileRepository helperProfiles;
  private TaskService taskService;
  private TaskRepository taskRepo;
  private ObjectMapper objectMapper;
  private RealtimePublisher realtime;
  private PushNotificationService pushNotifications;

  private MediatorService service;

  @BeforeEach
  public void setUp() {
    batches = mock(BookingBatchRepository.class);
    workers = mock(MediatorJobWorkerRepository.class);
    helperMediatorLinks = mock(HelperMediatorLinkRepository.class);
    users = mock(UserRepository.class);
    helperProfiles = mock(HelperProfileRepository.class);
    taskService = mock(TaskService.class);
    taskRepo = mock(TaskRepository.class);
    objectMapper = new ObjectMapper();
    realtime = mock(RealtimePublisher.class);
    pushNotifications = mock(PushNotificationService.class);

    service = new MediatorService(
        batches, workers, helperMediatorLinks, users, helperProfiles, taskService, taskRepo,
        objectMapper, realtime, pushNotifications, mock(PaymentRepository.class), mock(AppProperties.class),
        mock(com.helpinminutes.api.payments.service.PaymentLifecycleService.class));
  }

  @Test
  public void testGetJobThrowsNotFound() {
    UUID id = UUID.randomUUID();
    when(batches.findById(id)).thenReturn(Optional.empty());
    assertThrows(Exception.class, () -> service.getJob(UUID.randomUUID(), UserRole.MEDIATOR, id));
  }

  private BookingBatchEntity acceptedBatch(UUID batchId, UUID mediatorId, int requestedHelperCount) throws Exception {
    BookingBatchEntity batch = new BookingBatchEntity();
    batch.setId(batchId);
    batch.setStatus(BookingBatchStatus.MEDIATOR_ACCEPTED);
    batch.setMediatorId(mediatorId);
    batch.setCreatedByUserId(UUID.randomUUID());
    batch.setRequestedHelperCount(requestedHelperCount);
    batch.setTaskTemplateJson(objectMapper.writeValueAsString(new CreateTaskRequest(
        "Cleaning", "Clean apartment", TaskUrgency.NORMAL, 60, 50000L, 17.4, 78.4, "Hyderabad", null, null)));
    return batch;
  }

  @Test
  public void testAcceptJobSuccessfully() {
    UUID batchId = UUID.randomUUID();
    UUID mediatorId = UUID.randomUUID();

    BookingBatchEntity batch = new BookingBatchEntity();
    batch.setId(batchId);
    batch.setStatus(BookingBatchStatus.PENDING_MEDIATOR);
    batch.setCreatedByUserId(UUID.randomUUID());

    UserEntity mediator = new UserEntity();
    mediator.setId(mediatorId);
    mediator.setRole(UserRole.MEDIATOR);

    when(batches.findAndLockById(batchId)).thenReturn(Optional.of(batch));
    when(users.findById(mediatorId)).thenReturn(Optional.of(mediator));

    AcceptJobRequest req = new AcceptJobRequest(null, "Notes");
    MediatorJobResponse res = service.acceptJob(mediatorId, batchId, req);

    assertNotNull(res);
    assertEquals("MEDIATOR_ACCEPTED", res.status());
    assertEquals("Notes", res.mediatorNotes());
    verify(batches).save(batch);
  }

  @Test
  public void testDispatchRequiresAllRequestedHelpers() throws Exception {
    UUID batchId = UUID.randomUUID();
    UUID mediatorId = UUID.randomUUID();
    BookingBatchEntity batch = acceptedBatch(batchId, mediatorId, 2);
    MediatorJobWorkerEntity worker = new MediatorJobWorkerEntity();
    worker.setBatchId(batchId);
    worker.setHelperId(UUID.randomUUID());

    when(batches.findById(batchId)).thenReturn(Optional.of(batch));
    when(workers.findByBatchId(batchId)).thenReturn(List.of(worker));

    assertThrows(Exception.class, () -> service.dispatchJob(mediatorId, UserRole.MEDIATOR, batchId));
    verify(taskService, never()).createTaskForHelper(any(), any(), any());
  }

  @Test
  public void testDispatchBlocksFutureScheduledJob() throws Exception {
    UUID batchId = UUID.randomUUID();
    UUID mediatorId = UUID.randomUUID();
    BookingBatchEntity batch = acceptedBatch(batchId, mediatorId, 1);
    batch.setScheduledWindowStart(Instant.now().plusSeconds(3600));
    MediatorJobWorkerEntity worker = new MediatorJobWorkerEntity();
    worker.setBatchId(batchId);
    worker.setHelperId(UUID.randomUUID());

    when(batches.findById(batchId)).thenReturn(Optional.of(batch));
    when(workers.findByBatchId(batchId)).thenReturn(List.of(worker));
    when(taskService.createTaskForHelper(any(), any(), any())).thenReturn(new TaskEntity());

    service.dispatchJob(mediatorId, UserRole.MEDIATOR, batchId);
    verify(taskService, times(1)).createTaskForHelper(any(), any(), any());
  }

  @Test
  public void testAddWorkersDoesNotExceedRequestedCount() throws Exception {
    UUID batchId = UUID.randomUUID();
    UUID mediatorId = UUID.randomUUID();
    BookingBatchEntity batch = acceptedBatch(batchId, mediatorId, 1);
    MediatorJobWorkerEntity existing = new MediatorJobWorkerEntity();
    existing.setBatchId(batchId);
    existing.setHelperId(UUID.randomUUID());

    when(batches.findById(batchId)).thenReturn(Optional.of(batch));
    when(workers.findByBatchId(batchId)).thenReturn(List.of(existing));

    AddWorkersResponse res = service.addWorkers(mediatorId, UserRole.MEDIATOR, batchId, new AddWorkersRequest(List.of("9876543210"), List.of()));

    assertEquals(1, res.failureCount());
    assertEquals(0, res.successCount());
    verify(workers, never()).save(any(MediatorJobWorkerEntity.class));
  }

  @Test
  public void testStartJobRequiresCorrectOtp() throws Exception {
    UUID batchId = UUID.randomUUID();
    UUID mediatorId = UUID.randomUUID();
    BookingBatchEntity batch = acceptedBatch(batchId, mediatorId, 1);
    batch.setStatus(BookingBatchStatus.MEDIATOR_IN_PROGRESS);
    batch.setBatchStartOtp("123456");

    when(batches.findById(batchId)).thenReturn(Optional.of(batch));

    assertThrows(Exception.class, () -> service.startJob(mediatorId, UserRole.MEDIATOR, batchId, "000000"));
    service.startJob(mediatorId, UserRole.MEDIATOR, batchId, "123456");

    assertEquals(BookingBatchStatus.MEDIATOR_STARTED, batch.getStatus());
    verify(batches).save(batch);
  }

  @Test
  public void testCompleteJobRequiresCorrectOtp() throws Exception {
    UUID batchId = UUID.randomUUID();
    UUID mediatorId = UUID.randomUUID();
    BookingBatchEntity batch = acceptedBatch(batchId, mediatorId, 1);
    batch.setStatus(BookingBatchStatus.MEDIATOR_STARTED);
    batch.setBatchCompletionOtp("654321");

    when(batches.findById(batchId)).thenReturn(Optional.of(batch));
    when(workers.findByBatchId(batchId)).thenReturn(List.of());

    assertThrows(Exception.class, () -> service.completeJob(mediatorId, UserRole.MEDIATOR, batchId, "111111"));
    service.completeJob(mediatorId, UserRole.MEDIATOR, batchId, "654321");

    assertEquals(BookingBatchStatus.MEDIATOR_COMPLETED, batch.getStatus());
  }

}
