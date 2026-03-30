package com.helpinminutes.api.users.repo;

import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
  Optional<UserEntity> findByPhone(String phone);

  Optional<UserEntity> findByEmail(String email);

  java.util.List<UserEntity> findTop200ByRoleOrderByCreatedAtDesc(UserRole role);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserEntity u where u.id = :userId")
  Optional<UserEntity> findByIdForUpdate(@Param("userId") UUID userId);
}
