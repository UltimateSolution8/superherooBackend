package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.helpinminutes.api.errors.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The task-detail floor, enforced on the server.
 *
 * The app has required 3 characters of title and 10 of description since it
 * shipped, and the server required only "not blank". Anything that was not the app
 * could therefore book a job called "a" described as "b", and a partner would accept
 * work with no idea what it was. Rule 5: hiding a control in the app is
 * presentation, not enforcement.
 */
class TaskDetailRulesTest {

  private static final String GOOD_TITLE = "Move a sofa";
  private static final String GOOD_DESCRIPTION = "Help me shift a two-seater between rooms.";

  @Test
  void acceptsATaskAPartnerCouldActOn() {
    assertDoesNotThrow(() -> TaskService.requireUsableDetails(GOOD_TITLE, GOOD_DESCRIPTION));
  }

  @Test
  void refusesATitleShorterThanTheMinimum() {
    BadRequestException error = assertThrows(BadRequestException.class,
        () -> TaskService.requireUsableDetails("ab", GOOD_DESCRIPTION));
    assertTrue(error.getMessage().toLowerCase().contains("name"),
        "the message must name the field that failed: " + error.getMessage());
  }

  @Test
  void refusesADescriptionShorterThanTheMinimum() {
    BadRequestException error = assertThrows(BadRequestException.class,
        () -> TaskService.requireUsableDetails(GOOD_TITLE, "too short"));
    assertTrue(error.getMessage().toLowerCase().contains("describe"),
        "the message must name the field that failed: " + error.getMessage());
  }

  @Test
  void whitespaceIsNotContent() {
    assertThrows(BadRequestException.class,
        () -> TaskService.requireUsableDetails("   ", GOOD_DESCRIPTION));
    assertThrows(BadRequestException.class,
        () -> TaskService.requireUsableDetails(GOOD_TITLE, "          "));
  }

  @Test
  void nullIsRejectedRatherThanThrowingSomethingUnhelpful() {
    assertThrows(BadRequestException.class,
        () -> TaskService.requireUsableDetails(null, GOOD_DESCRIPTION));
    assertThrows(BadRequestException.class,
        () -> TaskService.requireUsableDetails(GOOD_TITLE, null));
  }

  @Test
  void theMinimumsMatchTheOnesTheAppEnforces() {
    // src/ux/bookingValidation.ts — MIN_TITLE_CHARS / MIN_DESCRIPTION_CHARS. If these
    // drift, the app accepts a task the server then rejects, at the last step of a
    // booking flow.
    assertDoesNotThrow(() -> TaskService.requireUsableDetails("a".repeat(TaskService.MIN_TITLE_CHARS),
        "b".repeat(TaskService.MIN_DESCRIPTION_CHARS)));
    assertThrows(BadRequestException.class,
        () -> TaskService.requireUsableDetails("a".repeat(TaskService.MIN_TITLE_CHARS - 1),
            "b".repeat(TaskService.MIN_DESCRIPTION_CHARS)));
  }
}
