package com.helpinminutes.api.matching;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MatchingDispatchWorkerTest {
  @Test
  void dispatchesThePersistedTaskThroughTheIdempotentMatcher() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    TaskRepository tasks = mock(TaskRepository.class);
    MatchingService matching = mock(MatchingService.class);
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));
    when(matching.dispatchOffers(task, true, 0)).thenReturn(List.of());

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    new MatchingDispatchWorker(tasks, matching, meters).handle(
        NotificationJob.matchingDispatch(taskId, UUID.randomUUID(), 0, true));

    verify(matching).dispatchOffers(task, true, 0);
    org.junit.jupiter.api.Assertions.assertEquals(
        1d, meters.counter("matching.dispatch.result", "outcome", "no_offer").count());
  }
}
