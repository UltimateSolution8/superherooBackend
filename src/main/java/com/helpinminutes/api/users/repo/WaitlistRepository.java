package com.helpinminutes.api.users.repo;

import com.helpinminutes.api.users.model.WaitlistEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WaitlistRepository extends JpaRepository<WaitlistEntity, UUID> {}
