package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.helpinminutes.api.common.TranslationService;
import com.helpinminutes.api.mediator.repo.MediatorJobWorkerRepository;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskMapperTest {

  @Test
  void mapsTaskListsWithBulkQueriesOnly() {
    UserRepository users = mock(UserRepository.class);
    TaskRepository tasks = mock(TaskRepository.class);
    TranslationService translations = mock(TranslationService.class);
    BookingBatchItemRepository batchItems = mock(BookingBatchItemRepository.class);
    MediatorJobWorkerRepository mediatorWorkers = mock(MediatorJobWorkerRepository.class);
    TaskMapper mapper = new TaskMapper(users, tasks, translations, batchItems, mediatorWorkers);

    when(users.findAllById(any())).thenReturn(List.of());
    when(tasks.findBuyerStats(any(), any())).thenReturn(List.of());
    when(tasks.findHelperStats(any(), any())).thenReturn(List.of());
    when(batchItems.findByTaskIdIn(any())).thenReturn(List.of());
    when(mediatorWorkers.findByTaskIdIn(any())).thenReturn(List.of());
    when(translations.translate(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));

    List<TaskEntity> input = List.of(task(UUID.randomUUID()), task(UUID.randomUUID()));
    assertEquals(2, mapper.toResponseList(input, false).size());

    verify(tasks).findBuyerStats(any(), any());
    verify(tasks).findHelperStats(any(), any());
    verify(batchItems).findByTaskIdIn(any());
    verify(mediatorWorkers).findByTaskIdIn(any());
    verify(batchItems, never()).findByTaskId(any());
    verify(mediatorWorkers, never()).findByTaskId(any());
  }

  @Test
  void marketplaceViewMasksCitizenIdentityAndExactLocationBeforeAcceptance() {
    UserRepository users = mock(UserRepository.class);
    TaskRepository tasks = mock(TaskRepository.class);
    TranslationService translations = mock(TranslationService.class);
    BookingBatchItemRepository batchItems = mock(BookingBatchItemRepository.class);
    MediatorJobWorkerRepository mediatorWorkers = mock(MediatorJobWorkerRepository.class);
    TaskMapper mapper = new TaskMapper(users, tasks, translations, batchItems, mediatorWorkers);
    when(users.findAllById(any())).thenReturn(List.of());
    when(tasks.findBuyerStats(any(), any())).thenReturn(List.of());
    when(batchItems.findByTaskIdIn(any())).thenReturn(List.of());
    when(mediatorWorkers.findByTaskIdIn(any())).thenReturn(List.of());
    when(translations.translate(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));

    TaskEntity searching = task(null);
    searching.setStatus(TaskStatus.SEARCHING);
    searching.setLat(17.38543);
    searching.setLng(78.48671);
    searching.setAddressText("12 Exact Street");
    searching.setLandmark("Private front gate");

    var response = mapper.toAvailableResponse(searching);
    assertEquals(17.39, response.lat());
    assertEquals(78.49, response.lng());
    assertEquals("Approximate task area", response.addressText());
    assertNull(response.buyerId());
    assertNull(response.buyerPhone());
    assertNull(response.buyerName());
    assertNull(response.landmark());
  }

  private TaskEntity task(UUID helperId) {
    TaskEntity task = new TaskEntity();
    task.setBuyerId(UUID.randomUUID());
    task.setAssignedHelperId(helperId);
    task.setTitle("Errand");
    task.setDescription("Pick up a parcel");
    task.setUrgency(TaskUrgency.NORMAL);
    task.setTimeMinutes(30);
    task.setBudgetPaise(10_000L);
    task.setLat(17.385);
    task.setLng(78.4867);
    task.setStatus(TaskStatus.COMPLETED);
    task.setPaymentCollectionMode(PaymentCollectionMode.PAY_AFTER_SERVICE);
    task.prePersist();
    return task;
  }
}
