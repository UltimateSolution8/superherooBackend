package com.helpinminutes.api.tasks.dto;

import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import java.time.Instant;
import java.util.UUID;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.tasks.model.TaskVerificationMode;

public record TaskResponse(
    UUID id,
    UUID buyerId,
    String buyerPhone,
    String buyerName,
    String title,
    String description,
    TaskUrgency urgency,
    Integer timeMinutes,
    Long budgetPaise,
    double lat,
    double lng,
    String addressText,
    Instant scheduledAt,
    TaskStatus status,
    UUID assignedHelperId,
    String helperPhone,
    String helperName,
    String arrivalOtp,
    String completionOtp,
    String arrivalSelfieUrl,
    Double arrivalSelfieLat,
    Double arrivalSelfieLng,
    String arrivalSelfieAddress,
    Instant arrivalSelfieCapturedAt,
    String completionSelfieUrl,
    Double completionSelfieLat,
    Double completionSelfieLng,
    String completionSelfieAddress,
    Instant completionSelfieCapturedAt,
    Instant workStartedAt,
    Double buyerRating,
    String buyerRatingComment,
    Instant buyerRatedAt,
    Double helperRating,
    String helperRatingComment,
    Instant helperRatedAt,
    Double helperAvgRating,
    Long helperCompletedCount,
    Double buyerAvgRating,
    Long buyerCompletedCount,
    String cancelReason,
    String cancelledByRole,
    Instant cancelledAt,
    Instant createdAt,
    String landmark,
    UUID recurringTaskId,
    UUID batchId,
    PaymentCollectionMode paymentCollectionMode,
    TaskVerificationMode verificationMode,
    /**
     * Distance from the requesting partner to the task, when the endpoint knows
     * where they are. Null on every other view.
     *
     * <p>Populated for {@code GET /tasks/available}. Its absence was a real bug:
     * the partner app read {@code distanceMeters ?? 0}, so every polled job
     * rendered as "0.0 km" and sorted ahead of genuinely nearer socket offers.
     */
    Double distanceMeters,
    /** Driving ETA in minutes for the same view; null when unknown. */
    Integer etaMinutes,
    /**
     * Platform commission on this task, in paise, at the rate that applies to it.
     *
     * <p>Exposed because the partner app previously showed "Deductions ₹0" and
     * called the citizen-facing price the partner's payout, which the ledger
     * flatly contradicted. Deriving it on the client would just move the guess.
     */
    Long platformCommissionPaise,
    /**
     * What the partner ends up with: {@code budgetPaise - platformCommissionPaise}.
     *
     * <p>How it reaches them depends on {@code paymentCollectionMode}. On
     * ONLINE_PREPAID it is paid out to their bank; on a direct-collection task
     * they take the full budget in hand and the commission is owed back to the
     * platform. Either way this is the number that is theirs.
     */
    Long helperEarningPaise
) {}
