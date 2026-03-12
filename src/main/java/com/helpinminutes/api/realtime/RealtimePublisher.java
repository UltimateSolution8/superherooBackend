package com.helpinminutes.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.AppProperties;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimePublisher {
  private final StringRedisTemplate redis;
  private final ObjectMapper om;
  private final AppProperties props;

  public RealtimePublisher(StringRedisTemplate redis, ObjectMapper om, AppProperties props) {
    this.redis = redis;
    this.om = om;
    this.props = props;
  }

  public void publish(String type, Map<String, Object> payload) {
    try {
      String msg = om.writeValueAsString(Map.of(
          "type", type,
          "payload", payload
      ));
      String channel = props.realtime().redisPubSubChannel();
      if (channel == null || channel.isBlank()) {
        channel = "him:rt:events";
      }
      redis.convertAndSend(channel, msg);
    } catch (Exception e) {
      // Best-effort realtime; API should not fail hard because socket gateway is down.
    }
  }
}
