package com.helpinminutes.api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RealtimeOutboxTest {

  @Test
  void publisherPersistsBeforeDispatchingWithTheSameStableId() {
    RealtimeOutboxRepository repository = mock(RealtimeOutboxRepository.class);
    RealtimeOutboxDispatcher dispatcher = mock(RealtimeOutboxDispatcher.class);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    RealtimePublisher publisher =
        new RealtimePublisher(repository, dispatcher, new ObjectMapper(), Runnable::run);

    publisher.publish("TASK_CREATED", Map.of("taskId", UUID.randomUUID().toString()));

    ArgumentCaptor<RealtimeOutboxEntity> saved =
        ArgumentCaptor.forClass(RealtimeOutboxEntity.class);
    verify(repository).save(saved.capture());
    assertEquals("PENDING", saved.getValue().getStatus());
    assertNotNull(saved.getValue().getId());
    verify(dispatcher).dispatchOne(saved.getValue().getId());
  }

  @Test
  void dispatcherMarksAnAcceptedDeliveryPublished() {
    RealtimeOutboxRepository repository = mock(RealtimeOutboxRepository.class);
    RealtimeTransport transport = mock(RealtimeTransport.class);
    RealtimeOutboxEntity event = pendingEvent();
    when(repository.claim(any(), any())).thenReturn(1);
    when(repository.findById(event.getId())).thenReturn(Optional.of(event));
    when(transport.deliver(any(), any(), any())).thenReturn(true);
    RealtimeOutboxDispatcher dispatcher = new RealtimeOutboxDispatcher(
        repository, transport, new ObjectMapper(), new SimpleMeterRegistry());

    dispatcher.dispatchOne(event.getId());

    assertEquals("PUBLISHED", event.getStatus());
    assertNotNull(event.getPublishedAt());
    assertEquals(0, event.getAttempts());
    verify(repository).save(event);
  }

  @Test
  void dispatcherSchedulesBackoffInsteadOfLosingAFailedDelivery() {
    RealtimeOutboxRepository repository = mock(RealtimeOutboxRepository.class);
    RealtimeTransport transport = mock(RealtimeTransport.class);
    RealtimeOutboxEntity event = pendingEvent();
    Instant before = Instant.now();
    when(repository.claim(any(), any())).thenReturn(1);
    when(repository.findById(event.getId())).thenReturn(Optional.of(event));
    when(transport.deliver(any(), any(), any())).thenReturn(false);
    RealtimeOutboxDispatcher dispatcher = new RealtimeOutboxDispatcher(
        repository, transport, new ObjectMapper(), new SimpleMeterRegistry());

    dispatcher.dispatchOne(event.getId());

    assertEquals("PENDING", event.getStatus());
    assertEquals(1, event.getAttempts());
    assertNotNull(event.getLastError());
    assertTrue(event.getNextAttemptAt().isAfter(before));
    verify(repository).save(event);
  }

  private static RealtimeOutboxEntity pendingEvent() {
    Instant now = Instant.now();
    RealtimeOutboxEntity event = new RealtimeOutboxEntity();
    event.setId(UUID.randomUUID());
    event.setEventType("TASK_CREATED");
    event.setPayloadJson("{\"taskId\":\"" + UUID.randomUUID() + "\"}");
    event.setStatus("PENDING");
    event.setAttempts(0);
    event.setNextAttemptAt(now);
    event.setCreatedAt(now);
    event.setUpdatedAt(now);
    return event;
  }
}
