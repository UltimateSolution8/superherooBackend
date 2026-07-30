package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.matching.MatchingService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.helpinminutes.api.support.service.SupportService;
import com.helpinminutes.api.support.dto.CreateTicketRequest;
import com.helpinminutes.api.support.model.SupportTicketCategory;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.payments.service.PaymentLifecycleService;

@Component
public class TaskStaleCleanupJob {
  private static final Logger log = LoggerFactory.getLogger(TaskStaleCleanupJob.class);

  private final TaskRepository tasks;
  private final TaskOfferRepository offers;
  private final MatchingService matching;
  private final SupportService supportService;
  private final Duration staleAssigned;
  private final Duration searchingTimeout;
  private final com.helpinminutes.api.common.SchedulerLock schedulerLock;
  private final long offerTtlSeconds;
  private final RealtimePublisher realtime;
  private final PaymentLifecycleService paymentLifecycle;

  public TaskStaleCleanupJob(
      com.helpinminutes.api.common.SchedulerLock schedulerLock,
      TaskRepository tasks,
      TaskOfferRepository offers,
      MatchingService matching,
      SupportService supportService,
      RealtimePublisher realtime,
      PaymentLifecycleService paymentLifecycle,
      com.helpinminutes.api.config.AppProperties props,
      @Value("${TASK_ASSIGNED_STALE_MINUTES:20}") long staleAssignedMinutes,
      // Was 120s, which equalled one offer window — a task was cancelled at the
      // exact moment its first round of offers lapsed, leaving no room to retry.
      @Value("${TASK_SEARCH_TIMEOUT_SECONDS:300}") long searchingTimeoutSeconds) {
    this.schedulerLock = schedulerLock;
    this.tasks = tasks;
    this.offers = offers;
    this.matching = matching;
    this.supportService = supportService;
    this.realtime = realtime;
    this.paymentLifecycle = paymentLifecycle;
    this.staleAssigned = Duration.ofMinutes(Math.max(5, staleAssignedMinutes));
    this.searchingTimeout = Duration.ofSeconds(Math.max(60, searchingTimeoutSeconds));
    this.offerTtlSeconds = props.matching().offerTtlSeconds();
  }

  @Scheduled(fixedDelayString = "${TASK_STALE_CLEANUP_MS:30000}")
  public void closeStaleAssignedTasks() {
    // Serialised across instances: two runs would auto-cancel the same tasks
    // twice, each issuing its own refund request and support ticket.
    schedulerLock.runExclusively("tasks.stale-cleanup", this::runCleanup);
  }

  /** Transaction is provided by SchedulerLock; see the note there. */
  public void runCleanup() {
    expireLapsedOffers();
    redispatchUnansweredSearchingTasks();
    closeTimedOutSearchingTasks();

    Instant cutoff = Instant.now().minus(staleAssigned);
    List<TaskEntity> stale = tasks.findTop100ByStatusAndUpdatedAtBefore(TaskStatus.ASSIGNED, cutoff);
    if (stale.isEmpty()) return;
    int closed = 0;
    Instant now = Instant.now();
    for (TaskEntity task : stale) {
      task.setStatus(TaskStatus.CANCELLED);
      task.setCancelReason("Auto-cancelled: no arrival confirmation");
      task.setCancelledByRole("SYSTEM");
      task.setCancelledByUserId(null);
      task.setCancelledAt(now);
      tasks.save(task);
      paymentLifecycle.requestTaskRefund(task);
      closed++;
    }
    log.info("Auto-cancelled {} assigned tasks older than {} minutes", closed, staleAssigned.toMinutes());
  }

  /**
   * Closes the acceptance window on offers nobody answered.
   *
   * Without this, offers stayed OFFERED indefinitely and dispatch treated every
   * past recipient as still holding a live offer.
   */
  private void expireLapsedOffers() {
    try {
      int expired = offers.expireLapsedOffers(Instant.now());
      if (expired > 0) log.info("Expired {} lapsed task offers", expired);
    } catch (Exception e) {
      log.warn("Could not expire lapsed offers", e);
    }
  }

  /**
   * Re-offers jobs that are still searching but have no live offer out.
   *
   * This is the retry loop the engine never had. Previously a task got exactly
   * one round of offers; if all five recipients ignored them, it simply waited
   * out the timeout and auto-cancelled — even though partners who came online a
   * few seconds later were never told about it.
   */
  private void redispatchUnansweredSearchingTasks() {
    Instant now = Instant.now();
    // Anything that has been searching for at least one offer window.
    Instant cutoff = now.minusSeconds(Math.max(30, offerTtlSeconds));
    List<TaskEntity> searching;
    try {
      searching = tasks.findTimedOutSearchingTasks(TaskStatus.SEARCHING, cutoff, PageRequest.of(0, 50));
    } catch (Exception e) {
      log.warn("Could not load searching tasks for re-dispatch", e);
      return;
    }
    if (searching.isEmpty()) return;

    List<java.util.UUID> taskIds = searching.stream()
        .filter(t -> t.getAssignedHelperId() == null)
        .map(TaskEntity::getId)
        .toList();
    if (taskIds.isEmpty()) return;

    java.util.Set<java.util.UUID> withLiveOffers =
        new java.util.HashSet<>(offers.findTaskIdsWithLiveOffers(taskIds, now));

    int redispatched = 0;
    for (TaskEntity task : searching) {
      if (task.getAssignedHelperId() != null) continue;
      if (withLiveOffers.contains(task.getId())) continue;
      try {
        // dispatchOffers re-checks status under a row lock, so a task that was
        // accepted between the query and here is a no-op.
        List<java.util.UUID> offered = matching.dispatchOffers(task);
        if (!offered.isEmpty()) redispatched++;
      } catch (Exception e) {
        log.warn("Re-dispatch failed for task {}", task.getId(), e);
      }
    }
    if (redispatched > 0) {
      log.info("Re-dispatched offers for {} unanswered searching tasks", redispatched);
    }
  }

  private void closeTimedOutSearchingTasks() {
    Instant now = Instant.now();
    Instant cutoff = now.minus(searchingTimeout);
    List<TaskEntity> stale = tasks.findTimedOutSearchingTasks(TaskStatus.SEARCHING, cutoff, PageRequest.of(0, 200));
    if (stale.isEmpty()) return;
    int closed = 0;
    for (TaskEntity task : stale) {
      if (task.getAssignedHelperId() != null) {
        continue;
      }
      task.setStatus(TaskStatus.CANCELLED);
      task.setCancelReason("No helper accepted your task in time. Please try again.");
      task.setCancelledByRole("SYSTEM");
      task.setCancelledByUserId(null);
      task.setCancelledAt(now);
      tasks.save(task);
      paymentLifecycle.requestTaskRefund(task);
      closed++;
      try {
        CreateTicketRequest ticketReq = new CreateTicketRequest(
            SupportTicketCategory.CANCELLATION,
            "Task Auto-Cancelled: Timeout",
            "Task with title '" + task.getTitle() + "' and ID " + task.getId()
                + " was auto-cancelled because no helper accepted it within "
                + searchingTimeout.toSeconds() + " seconds.",
            task.getId().toString()
        );
        supportService.createTicket(task.getBuyerId(), UserRole.BUYER, ticketReq);
      } catch (Exception e) {
        log.warn("Could not create support ticket for timed out task {}", task.getId(), e);
      }
      try {
        realtime.publish(
            "task_status_changed",
            java.util.Map.of(
                "taskId", task.getId().toString(),
                "buyerId", task.getBuyerId().toString(),
                "status", TaskStatus.CANCELLED.name()));
      } catch (Exception e) {
        log.warn("Could not publish timeout cancellation for task {}", task.getId(), e);
      }
    }
    if (closed > 0) {
      log.info("Auto-cancelled {} searching tasks after {} seconds timeout", closed, searchingTimeout.toSeconds());
    }
  }

}
