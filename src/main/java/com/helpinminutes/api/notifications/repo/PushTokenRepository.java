package com.helpinminutes.api.notifications.repo;

import com.helpinminutes.api.notifications.model.PushTokenEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushTokenRepository extends JpaRepository<PushTokenEntity, UUID> {
  Optional<PushTokenEntity> findByToken(String token);

  List<PushTokenEntity> findAllByUserIdIn(List<UUID> userIds);
}
