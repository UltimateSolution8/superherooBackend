package com.helpinminutes.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimePublisher {
  private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper om;
  private final AppProperties props;
  private final HttpClient http;

  public RealtimePublisher(StringRedisTemplate redis, ObjectMapper om, AppProperties props) {
    this.redis = redis;
    this.om = om;
    this.props = props;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.max(200, props.realtime().publishHttpTimeoutMs())))
        .build();
  }

  public void publish(String type, Map<String, Object> payload) {
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> envelope = Map.of(
        "type", type,
        "eventId", eventId,
        "publishedAt", Instant.now().toString(),
        "payload", payload
    );
    try {
      String msg = om.writeValueAsString(envelope);
      String channel = props.realtime().redisPubSubChannel();
      if (channel == null || channel.isBlank()) {
        channel = "him:rt:events";
      }
      Long delivered = redis.convertAndSend(channel, msg);
      if (delivered == null || delivered <= 0) {
        log.warn("Realtime Redis publish had no subscribers type={} eventId={} channel={}", type, eventId, channel);
      }
    } catch (Exception e) {
      log.warn("Realtime Redis publish failed type={} eventId={}", type, eventId, e);
    }

    publishHttpFallback(envelope);
  }

  private void publishHttpFallback(Map<String, Object> envelope) {
    String publishUrl = props.realtime().publishHttpUrl();
    if (publishUrl == null || publishUrl.isBlank()) {
      return;
    }
    try {
      String body = om.writeValueAsString(envelope);
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(publishUrl))
          .timeout(Duration.ofMillis(Math.max(200, props.realtime().publishHttpTimeoutMs())))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
      String secret = props.realtime().publishHttpSecret();
      if (secret != null && !secret.isBlank()) {
        requestBuilder.header("x-realtime-secret", secret);
      }
      HttpRequest req = requestBuilder.build();
      HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
      if (resp.statusCode() >= 400) {
        log.warn("Realtime HTTP publish failed status={} url={} eventId={}",
            resp.statusCode(), publishUrl, envelope.get("eventId"));
      }
    } catch (Exception e) {
      log.warn("Realtime HTTP publish failed url={} eventId={}", publishUrl, envelope.get("eventId"), e);
    }
  }
}
