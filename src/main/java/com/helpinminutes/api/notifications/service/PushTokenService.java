package com.helpinminutes.api.notifications.service;

import com.helpinminutes.api.notifications.model.PushTokenEntity;
import com.helpinminutes.api.notifications.repo.PushTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushTokenService {
  private final PushTokenRepository tokens;

  public PushTokenService(PushTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Transactional
  public void register(UUID userId, String token, String platform) {
    var existing = tokens.findByToken(token).orElse(null);
    if (existing != null) {
      existing.setUserId(userId);
      existing.setPlatform(platform);
      existing.setToken(token);
      tokens.save(existing);
      return;
    }
    PushTokenEntity entity = new PushTokenEntity();
    entity.setUserId(userId);
    entity.setToken(token);
    entity.setPlatform(platform);
    tokens.save(entity);
  }

  public List<PushTokenEntity> getTokensForUsers(List<UUID> userIds) {
    if (userIds == null || userIds.isEmpty()) return List.of();
    return tokens.findAllByUserIdIn(userIds);
  }

  @Transactional
  public void touch(PushTokenEntity token) {
    token.setToken(token.getToken());
    tokens.save(token);
  }
}
