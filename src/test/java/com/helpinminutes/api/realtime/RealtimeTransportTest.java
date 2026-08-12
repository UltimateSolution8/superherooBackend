package com.helpinminutes.api.realtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.AppProperties;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RealtimeTransportTest {

  @Test
  void taskEventIsNotAcknowledgedUntilRoutingStateIsDurable() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForSet()).thenThrow(new IllegalStateException("redis state unavailable"));
    when(redis.convertAndSend(anyString(), anyString())).thenReturn(1L);
    RealtimeTransport transport = new RealtimeTransport(redis, new ObjectMapper(), properties());

    boolean accepted = transport.deliver(
        UUID.randomUUID().toString(),
        "task_created",
        Map.of("taskId", UUID.randomUUID().toString(), "buyerId", UUID.randomUUID().toString()));

    assertFalse(accepted, "the outbox must retry until task access state is persisted");
  }

  @Test
  void unrelatedTargetedEventCanUseRedisWithoutTaskRoutingState() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.convertAndSend(anyString(), anyString())).thenReturn(1L);
    RealtimeTransport transport = new RealtimeTransport(redis, new ObjectMapper(), properties());

    assertTrue(transport.deliver(
        UUID.randomUUID().toString(),
        "chat_message_received",
        Map.of("targetUserId", UUID.randomUUID().toString())));
  }

  private static AppProperties properties() {
    AppProperties props = mock(AppProperties.class);
    when(props.realtime()).thenReturn(new AppProperties.Realtime("him:rt:events", null, null, 1500));
    return props;
  }
}
