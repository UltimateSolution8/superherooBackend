package com.helpinminutes.api.users.repo;

import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
  Optional<UserEntity> findByPhone(String phone);

  Optional<UserEntity> findByEmail(String email);

  java.util.List<UserEntity> findTop200ByRoleOrderByCreatedAtDesc(UserRole role);
}
