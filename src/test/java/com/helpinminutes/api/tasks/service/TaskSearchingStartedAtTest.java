package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.model.TaskVerificationMode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskSearchingStartedAtTest {

  @Test
  void taskResponseCarriesSearchingStartedAt() {
    Instant now = Instant.now();
    TaskResponse response = new TaskResponse(
        UUID.randomUUID(), // id
        UUID.randomUUID(), // buyerId
        "9876543210", // buyerPhone
        "Buyer", // buyerName
        "Title", // title
        "Description", // description
        TaskUrgency.LOW, // urgency
        30, // timeMinutes
        50000L, // budgetPaise
        17.3850, // lat
        78.4867, // lng
        "Hyderabad", // addressText
        null, // scheduledAt
        TaskStatus.SEARCHING, // status
        null, // assignedHelperId
        null, // helperPhone
        null, // helperName
        null, // arrivalOtp
        null, // completionOtp
        null, // arrivalSelfieUrl
        null, // arrivalSelfieLat
        null, // arrivalSelfieLng
        null, // arrivalSelfieAddress
        null, // arrivalSelfieCapturedAt
        null, // completionSelfieUrl
        null, // completionSelfieLat
        null, // completionSelfieLng
        null, // completionSelfieAddress
        null, // completionSelfieCapturedAt
        null, // workStartedAt
        null, // buyerRating
        null, // buyerRatingComment
        null, // buyerRatedAt
        null, // helperRating
        null, // helperRatingComment
        null, // helperRatedAt
        null, // helperAvgRating
        null, // helperCompletedCount
        null, // buyerAvgRating
        null, // buyerCompletedCount
        null, // cancelReason
        null, // cancelledByRole
        null, // cancelledAt
        now.minusSeconds(60), // createdAt
        now, // searchingStartedAt
        "Landmark", // landmark
        null, // recurringTaskId
        null, // batchId
        PaymentCollectionMode.PAY_AFTER_SERVICE, // paymentCollectionMode
        TaskVerificationMode.OTP_ONLY, // verificationMode
        null, // distanceMeters
        null, // etaMinutes
        7500L, // platformCommissionPaise
        42500L // helperEarningPaise
    );

    assertEquals(now, response.searchingStartedAt());
  }

  @Test
  void taskEntityBeginSearchingStampsSearchingStartedAt() {
    TaskEntity task = new TaskEntity();
    task.setStatus(TaskStatus.ADMIN_REVIEW);
    assertNull(task.getSearchingStartedAt());

    Instant searchTime = Instant.now();
    task.beginSearching(searchTime);

    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    assertEquals(searchTime, task.getSearchingStartedAt());
  }

  @Test
  void taskEntityPrePersistSetsSearchingStartedAtWhenSearching() {
    TaskEntity task = new TaskEntity();
    task.setStatus(TaskStatus.SEARCHING);
    assertNull(task.getSearchingStartedAt());

    task.prePersist();

    assertNotNull(task.getSearchingStartedAt());
    assertEquals(task.getCreatedAt(), task.getSearchingStartedAt());
  }
}
