package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.helpinminutes.api.mediator.repo.MediatorJobWorkerRepository;

@Component
public class TaskMapper {
    private final UserRepository users;
    private final TaskRepository tasks;
    private final com.helpinminutes.api.common.TranslationService translationService;
    private final BookingBatchItemRepository bookingBatchItems;
    private final MediatorJobWorkerRepository mediatorWorkers;

    public TaskMapper(
            UserRepository users,
            TaskRepository tasks,
            com.helpinminutes.api.common.TranslationService translationService,
            BookingBatchItemRepository bookingBatchItems,
            MediatorJobWorkerRepository mediatorWorkers) {
        this.users = users;
        this.tasks = tasks;
        this.translationService = translationService;
        this.bookingBatchItems = bookingBatchItems;
        this.mediatorWorkers = mediatorWorkers;
    }

    public TaskResponse toResponse(TaskEntity t, boolean includeOtp) {
        return toResponseList(Collections.singletonList(t), includeOtp).get(0);
    }

    public List<TaskResponse> toResponseList(List<TaskEntity> taskEntities, boolean includeOtp) {
        if (taskEntities == null || taskEntities.isEmpty()) {
            return Collections.emptyList();
        }

        Set<UUID> buyerIds = taskEntities.stream()
                .map(TaskEntity::getBuyerId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> helperIds = taskEntities.stream()
                .map(TaskEntity::getAssignedHelperId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> userIds = Stream.concat(buyerIds.stream(), helperIds.stream())
                .collect(Collectors.toSet());
        Set<UUID> taskIds = taskEntities.stream().map(TaskEntity::getId).collect(Collectors.toSet());

        Map<UUID, UserEntity> userMap = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        Map<UUID, UserStats> buyerStats = statsMap(
                buyerIds.isEmpty() ? List.of() : tasks.findBuyerStats(buyerIds, TaskStatus.COMPLETED));
        Map<UUID, UserStats> helperStats = statsMap(
                helperIds.isEmpty() ? List.of() : tasks.findHelperStats(helperIds, TaskStatus.COMPLETED));

        Map<UUID, UUID> batchIdsByTask = bookingBatchItems.findByTaskIdIn(taskIds).stream()
                .collect(Collectors.toMap(
                        com.helpinminutes.api.batches.model.BookingBatchItemEntity::getTaskId,
                        com.helpinminutes.api.batches.model.BookingBatchItemEntity::getBatchId,
                        (first, ignored) -> first));
        mediatorWorkers.findByTaskIdIn(taskIds).forEach(worker ->
                batchIdsByTask.putIfAbsent(worker.getTaskId(), worker.getBatchId()));

        return taskEntities.stream()
                .map(t -> mapToResponse(t, includeOtp, userMap, buyerStats, helperStats, batchIdsByTask))
                .collect(Collectors.toList());
    }

    private TaskResponse mapToResponse(TaskEntity t, boolean includeOtp, Map<UUID, UserEntity> userMap,
            Map<UUID, UserStats> buyerStatsMap, Map<UUID, UserStats> helperStatsMap,
            Map<UUID, UUID> batchIdsByTask) {
        UserEntity buyer = userMap.get(t.getBuyerId());
        UserEntity helper = userMap.get(t.getAssignedHelperId());
        UserStats buyerStats = buyerStatsMap.get(t.getBuyerId());
        UserStats helperStats = helperStatsMap.get(t.getAssignedHelperId());

        String buyerPhone = buyer != null ? buyer.getPhone() : null;
        String buyerName = buyer != null
                ? (buyer.getDisplayName() != null && !buyer.getDisplayName().isBlank() ? buyer.getDisplayName()
                        : buyer.getPhone())
                : null;
        String helperPhone = helper != null ? helper.getPhone() : null;
        String helperName = helper != null
                ? (helper.getDisplayName() != null && !helper.getDisplayName().isBlank() ? helper.getDisplayName()
                        : helper.getPhone())
                : null;

        String acceptLanguage = getAcceptLanguageHeader();
        String translatedTitle = translationService.translate(t.getTitle(), acceptLanguage);
        String translatedDescription = translationService.translate(t.getDescription(), acceptLanguage);

        UUID batchId = batchIdsByTask.get(t.getId());

        return new TaskResponse(
                t.getId(),
                t.getBuyerId(),
                buyerPhone,
                buyerName,
                translatedTitle,
                translatedDescription,
                t.getUrgency(),
                t.getTimeMinutes(),
                t.getBudgetPaise(),
                t.getLat(),
                t.getLng(),
                t.getAddressText(),
                t.getScheduledAt(),
                t.getStatus(),
                t.getAssignedHelperId(),
                helperPhone,
                helperName,
                includeOtp ? t.getArrivalOtp() : null,
                includeOtp ? t.getCompletionOtp() : null,
                t.getArrivalSelfieUrl(),
                t.getArrivalSelfieLat(),
                t.getArrivalSelfieLng(),
                t.getArrivalSelfieAddress(),
                t.getArrivalSelfieCapturedAt(),
                t.getCompletionSelfieUrl(),
                t.getCompletionSelfieLat(),
                t.getCompletionSelfieLng(),
                t.getCompletionSelfieAddress(),
                t.getCompletionSelfieCapturedAt(),
                t.getWorkStartedAt(),
                t.getBuyerRating() != null ? t.getBuyerRating().doubleValue() : null,
                t.getBuyerRatingComment(),
                t.getBuyerRatedAt(),
                t.getHelperRating() != null ? t.getHelperRating().doubleValue() : null,
                t.getHelperRatingComment(),
                t.getHelperRatedAt(),
                helperStats != null ? helperStats.avgRating() : null,
                helperStats != null ? helperStats.completedCount() : null,
                buyerStats != null ? buyerStats.avgRating() : null,
                buyerStats != null ? buyerStats.completedCount() : null,
                t.getCancelReason(),
                t.getCancelledByRole(),
                t.getCancelledAt(),
                t.getCreatedAt(),
                t.getLandmark(),
                t.getRecurringTaskId(),
                batchId,
                t.getPaymentCollectionMode());
    }

    private String getAcceptLanguageHeader() {
        try {
            org.springframework.web.context.request.RequestAttributes attrs = 
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes) {
                jakarta.servlet.http.HttpServletRequest request = 
                    ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
                return request.getHeader("Accept-Language");
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private Map<UUID, UserStats> statsMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> (UUID) row[0],
                row -> new UserStats(((Number) row[1]).longValue(),
                        row[2] == null ? null : ((Number) row[2]).doubleValue())));
    }

    private record UserStats(Long completedCount, Double avgRating) {
    }
}
