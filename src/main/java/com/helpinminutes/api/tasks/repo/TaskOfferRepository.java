package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskOfferRepository extends JpaRepository<TaskOfferEntity, UUID> {
  Optional<TaskOfferEntity> findByTaskIdAndHelperId(UUID taskId, UUID helperId);

  List<TaskOfferEntity> findAllByTaskId(UUID taskId);

  @Modifying
  @Query(
      "update TaskOfferEntity o set o.status = :newStatus, o.respondedAt = :respondedAt "
          + "where o.taskId = :taskId and o.helperId = :helperId and o.status = :expectedStatus")
  int respond(
      @Param("taskId") UUID taskId,
      @Param("helperId") UUID helperId,
      @Param("expectedStatus") TaskOfferStatus expectedStatus,
      @Param("newStatus") TaskOfferStatus newStatus,
      @Param("respondedAt") Instant respondedAt);

  @Modifying
  @Query(
      "update TaskOfferEntity o set o.status = :newStatus "
          + "where o.taskId = :taskId and o.status = :expectedStatus and o.helperId <> :winnerId")
  int expireOthers(
      @Param("taskId") UUID taskId,
      @Param("expectedStatus") TaskOfferStatus expectedStatus,
      @Param("newStatus") TaskOfferStatus newStatus,
      @Param("winnerId") UUID winnerId);
}
