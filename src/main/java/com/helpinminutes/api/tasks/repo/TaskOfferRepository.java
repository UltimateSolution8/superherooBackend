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

  boolean existsByTaskIdAndHelperIdAndStatusAndExpiresAtAfter(
      UUID taskId,
      UUID helperId,
      TaskOfferStatus status,
      Instant expiresAt);

  List<TaskOfferEntity> findAllByTaskId(UUID taskId);

  boolean existsByTaskId(UUID taskId);

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

  /**
   * Sweeps offers whose acceptance window has closed.
   *
   * Nothing did this before: offers expired only by wall-clock comparison at
   * read time, so rows stayed OFFERED forever. Because dispatch skipped any
   * helper with an existing offer row, every helper ever offered a task was
   * permanently excluded from it.
   */
  @Modifying
  @Query(
      "update TaskOfferEntity o set o.status = com.helpinminutes.api.tasks.model.TaskOfferStatus.EXPIRED "
          + "where o.status = com.helpinminutes.api.tasks.model.TaskOfferStatus.OFFERED "
          + "and o.expiresAt <= :now")
  int expireLapsedOffers(@Param("now") Instant now);

  /** Offers still within their acceptance window. */
  @Query("select o from TaskOfferEntity o where o.taskId = :taskId "
      + "and o.status = com.helpinminutes.api.tasks.model.TaskOfferStatus.OFFERED "
      + "and o.expiresAt > :now")
  List<TaskOfferEntity> findLiveOffers(@Param("taskId") UUID taskId, @Param("now") Instant now);

  /** Tasks still searching that have no live offer — candidates for re-dispatch. */
  @Query("select distinct o.taskId from TaskOfferEntity o where o.taskId in :taskIds "
      + "and o.status = com.helpinminutes.api.tasks.model.TaskOfferStatus.OFFERED "
      + "and o.expiresAt > :now")
  List<UUID> findTaskIdsWithLiveOffers(
      @Param("taskIds") java.util.Collection<UUID> taskIds, @Param("now") Instant now);
}
