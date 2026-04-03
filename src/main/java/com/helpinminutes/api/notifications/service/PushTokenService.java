package com.helpinminutes.api.notifications.service;

import com.helpinminutes.api.notifications.model.PushTokenEntity;
import com.helpinminutes.api.notifications.repo.PushTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushTokenService {
  private static final Logger log = LoggerFactory.getLogger(PushTokenService.class);

  private final PushTokenRepository tokens;

  public PushTokenService(PushTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Transactional
  public void register(UUID userId, String token, String platform) {
    String safeToken = token == null ? "" : token.trim();
    if (safeToken.isEmpty()) return;
    var existing = tokens.findByToken(safeToken).orElse(null);
    if (existing != null) {
      existing.setUserId(userId);
      existing.setPlatform(platform);
      existing.setToken(safeToken);
      tokens.save(existing);
      log.info("Push token updated user={} platform={} tokenPrefix={}", userId, platform, tokenPrefix(safeToken));
      return;
    }
    PushTokenEntity entity = new PushTokenEntity();
    entity.setUserId(userId);
    entity.setToken(safeToken);
    entity.setPlatform(platform);
    tokens.save(entity);
    log.info("Push token registered user={} platform={} tokenPrefix={}", userId, platform, tokenPrefix(safeToken));
  }

  public List<PushTokenEntity> getTokensForUsers(List<UUID> userIds) {
    if (userIds == null || userIds.isEmpty()) return List.of();
    return tokens.findAllByUserIdIn(userIds);
  }

  @Transactional
  public long removeTokens(List<String> tokenValues) {
    if (tokenValues == null || tokenValues.isEmpty()) return 0L;
    return tokens.deleteByTokenIn(tokenValues);
  }

  @Transactional
  public long purgeStaleTokens(Instant cutoff) {
    if (cutoff == null) return 0L;
    return tokens.deleteByLastSeenAtBefore(cutoff);
  }

  @Transactional
  public void touch(PushTokenEntity token) {
    token.setToken(token.getToken());
    tokens.save(token);
  }

  private String tokenPrefix(String token) {
    if (token == null || token.isBlank()) return "";
    return token.length() <= 10 ? token : token.substring(0, 10) + "...";
  }
}
