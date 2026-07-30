package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.helpinminutes.api.tasks.model.TaskStatus;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the trapped-user bug.
 *
 * cancelTask originally allowed only PAYMENT_PENDING, SCHEDULED_PENDING,
 * SEARCHING and ASSIGNED. A booking that AI moderation routed to ADMIN_REVIEW
 * (or that was still AI_PENDING) could not be cancelled by the citizen at all —
 * they were stuck waiting on a human moderator with no way out, and no way to
 * rebook without leaving a dangling task behind.
 *
 * Every pre-arrival state must stay cancellable; every state from ARRIVED
 * onwards must not, because work has started and money is in play.
 */
class CancellableStatusTest {

  /** States a citizen must always be able to walk away from. */
  private static final Set<TaskStatus> MUST_BE_CANCELLABLE = Set.of(
      TaskStatus.AI_PENDING,
      TaskStatus.ADMIN_REVIEW,
      TaskStatus.AI_APPROVED,
      TaskStatus.ADMIN_APPROVED,
      TaskStatus.PAYMENT_PENDING,
      TaskStatus.SCHEDULED_PENDING,
      TaskStatus.SEARCHING,
      TaskStatus.ASSIGNED);

  /** Work has begun or the task is already closed. */
  private static final Set<TaskStatus> MUST_NOT_BE_CANCELLABLE = Set.of(
      TaskStatus.ARRIVED,
      TaskStatus.STARTED,
      TaskStatus.COMPLETED,
      TaskStatus.CANCELLED,
      TaskStatus.ADMIN_REJECTED);

  /**
   * Reads the guard straight out of the compiled source rather than duplicating
   * the rule. A test that reimplements the logic it is checking can stay green
   * while production diverges — that already happened once in this codebase.
   */
  private static String cancelGuardSource() throws Exception {
    java.nio.file.Path path = java.nio.file.Path.of(
        "src/main/java/com/helpinminutes/api/tasks/service/TaskService.java");
    String source = java.nio.file.Files.readString(path);
    int start = source.indexOf("public TaskResponse cancelTask(");
    assertTrue(start > 0, "cancelTask no longer exists");
    int guardEnd = source.indexOf("Task can only be cancelled before arrival", start);
    assertTrue(guardEnd > start, "the cancellable-status guard has moved or changed shape");
    return source.substring(start, guardEnd);
  }

  @Test
  void everyPreArrivalStateRemainsCancellable() throws Exception {
    String guard = cancelGuardSource();
    List<String> missing = MUST_BE_CANCELLABLE.stream()
        .map(Enum::name)
        .filter(name -> !guard.contains("TaskStatus." + name))
        .toList();
    assertTrue(missing.isEmpty(),
        "these statuses are no longer cancellable, which traps the citizen: " + missing);
  }

  @Test
  void statesAfterArrivalAreNotCancellable() throws Exception {
    String guard = cancelGuardSource();
    List<String> wrongly = MUST_NOT_BE_CANCELLABLE.stream()
        .map(Enum::name)
        .filter(name -> guard.contains("TaskStatus." + name))
        .toList();
    assertTrue(wrongly.isEmpty(),
        "work has started or the task is closed; these must not be cancellable: " + wrongly);
  }

  @Test
  void cancelTaskIsStillReachable() {
    boolean found = false;
    for (Method m : TaskService.class.getDeclaredMethods()) {
      if (m.getName().equals("cancelTask")) {
        found = true;
        break;
      }
    }
    assertTrue(found, "TaskService.cancelTask was removed or renamed");
  }
}
