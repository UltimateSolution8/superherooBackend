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

  /**
   * Removes a device token at sign-out.
   *
   * Ownership is checked so one user cannot unregister another's device. Without
   * this the token stays bound to the signed-out account and the device keeps
   * receiving that person's notifications — including to whoever signs in next.
   */
  @Transactional
  public void unregister(UUID userId, String token) {
    String safeToken = token == null ? "" : token.trim();
    if (safeToken.isEmpty()) return;
    tokens.findByToken(safeToken)
        .filter(existing -> existing.getUserId().equals(userId))
        .ifPresent(existing -> {
          tokens.delete(existing);
          log.info("Push token unregistered user={} tokenPrefix={}", userId, tokenPrefix(safeToken));
        });
  }

  public List<PushTokenEntity> getTokensForUsers(List<UUID> userIds) {
    if (userIds == null || userIds.isEmpty()) return List.of();
    return tokens.findAllByUserIdIn(userIds);
  }

  public long countRegisteredTokens() {
    return tokens.count();
  }

  @Transactional
  public long removeTokens(List<String> tokenValues) {
    if (tokenValues == null || tokenValues.isEmpty()) return 0L;
    return tokens.deleteByTokenIn(tokenValues);
  }

  /**
   * Deletes device tokens not seen since {@code cutoff}. Driven by
   * {@code RetentionJob}; before that this method existed but had no caller, so
   * dead device registrations accumulated indefinitely.
   */
  @Transactional
  public long purgeStaleTokens(Instant cutoff) {
    if (cutoff == null) return 0L;
    return tokens.deleteStaleBefore(cutoff);
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
