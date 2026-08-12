package com.helpinminutes.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Low-level, retryable delivery used only by the transactional outbox. */
@Service
public class RealtimeTransport {
  private static final Logger log = LoggerFactory.getLogger(RealtimeTransport.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final AppProperties props;
  private final HttpClient http;

  public RealtimeTransport(StringRedisTemplate redis, ObjectMapper objectMapper, AppProperties props) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.props = props;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.max(200, props.realtime().publishHttpTimeoutMs())))
        .build();
  }

  public boolean deliver(String eventId, String type, Map<String, Object> payload) {
    Map<String, Object> envelope = Map.of(
        "type", type,
        "eventId", eventId,
        "publishedAt", Instant.now().toString(),
        "payload", payload);
    boolean routingStatePersisted = true;
    try {
      persistTaskRoutingState(type, payload);
    } catch (Exception e) {
      // The HTTP gateway may still be reachable when the backend's Redis path is
      // impaired. Deliver with the stable event id, but do not acknowledge the
      // outbox row: a later retry must restore restart/subscription authorization.
      routingStatePersisted = false;
      log.warn("Realtime routing-state persistence failed type={} eventId={}: {}",
          type, eventId, e.getMessage());
    }

    try {
      String channel = props.realtime().redisPubSubChannel();
      if (channel == null || channel.isBlank()) channel = "him:rt:events";
      Long delivered = redis.convertAndSend(channel, objectMapper.writeValueAsString(envelope));
      if (delivered != null && delivered > 0) {
        if (routingStatePersisted) return true;
        log.warn("Realtime event published but routing state is pending type={} eventId={}", type, eventId);
      } else {
        log.warn("Realtime Redis publish had no subscribers type={} eventId={}", type, eventId);
      }
    } catch (Exception e) {
      log.warn("Realtime Redis publish failed type={} eventId={}", type, eventId, e);
    }
    return publishHttp(envelope) && routingStatePersisted;
  }

  private boolean publishHttp(Map<String, Object> envelope) {
    String publishUrl = props.realtime().publishHttpUrl();
    if (publishUrl == null || publishUrl.isBlank()) return false;
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create(publishUrl))
          .timeout(Duration.ofMillis(Math.max(200, props.realtime().publishHttpTimeoutMs())))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(envelope)));
      String secret = props.realtime().publishHttpSecret();
      if (secret != null && !secret.isBlank()) builder.header("x-realtime-secret", secret);
      HttpResponse<Void> response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
      return response.statusCode() >= 200 && response.statusCode() < 300;
    } catch (Exception e) {
      log.warn("Realtime HTTP publish failed eventId={}", envelope.get("eventId"), e);
      return false;
    }
  }

  private void persistTaskRoutingState(String type, Map<String, Object> payload) {
    if (type == null || payload == null) return;
    String normalized = type.toUpperCase(Locale.ROOT).replace('.', '_');
    if (!Set.of("TASK_CREATED", "TASK_ASSIGNED", "TASK_STATUS_CHANGED").contains(normalized)) return;
    Object taskIdValue = payload.get("taskId");
    if (taskIdValue == null) return;
    String taskId = String.valueOf(taskIdValue);
    String accessKey = "him:rt:task:" + taskId + ":access";
    String assignmentKey = "him:rt:task:" + taskId + ":assignment";
    Object buyerId = payload.get("buyerId");
    Object helperId = payload.get("helperId");
    if (buyerId != null) redis.opsForSet().add(accessKey, String.valueOf(buyerId));
    if (helperId != null) redis.opsForSet().add(accessKey, String.valueOf(helperId));
    redis.expire(accessKey, Duration.ofDays(7));
    if ("TASK_ASSIGNED".equals(normalized) && helperId != null) {
      Map<String, String> assignment = new HashMap<>();
      assignment.put("taskId", taskId);
      assignment.put("helperId", String.valueOf(helperId));
      assignment.put("buyerId", buyerId == null ? "" : String.valueOf(buyerId));
      redis.opsForHash().putAll(assignmentKey, assignment);
      redis.expire(assignmentKey, Duration.ofHours(6));
    }
    Object status = payload.get("status");
    if ("TASK_STATUS_CHANGED".equals(normalized) && status != null
        && Set.of("COMPLETED", "CANCELLED").contains(String.valueOf(status).toUpperCase(Locale.ROOT))) {
      redis.delete(assignmentKey);
      redis.expire(accessKey, Duration.ofHours(1));
    }
  }
}
