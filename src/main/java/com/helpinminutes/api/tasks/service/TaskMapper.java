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

@Component
public class TaskMapper {
    private final UserRepository users;
    private final TaskRepository tasks;

    public TaskMapper(UserRepository users, TaskRepository tasks) {
        this.users = users;
        this.tasks = tasks;
    }

    public TaskResponse toResponse(TaskEntity t, boolean includeOtp) {
        return toResponseList(Collections.singletonList(t), includeOtp).get(0);
    }

    public List<TaskResponse> toResponseList(List<TaskEntity> taskEntities, boolean includeOtp) {
        if (taskEntities == null || taskEntities.isEmpty()) {
            return Collections.emptyList();
        }

        Set<UUID> userIds = taskEntities.stream()
                .flatMap(t -> Stream.of(t.getBuyerId(), t.getAssignedHelperId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, UserEntity> userMap = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        // Note: For production at scale, these aggregates should be cached in
        // UserEntity or HelperProfile.
        // For now, we optimize by fetching them once per unique user in the list.
        Map<UUID, UserStats> statsMap = userIds.stream()
                .collect(Collectors.toMap(uid -> uid, uid -> fetchUserStats(uid)));

        return taskEntities.stream()
                .map(t -> mapToResponse(t, includeOtp, userMap, statsMap))
                .collect(Collectors.toList());
    }

    private TaskResponse mapToResponse(TaskEntity t, boolean includeOtp, Map<UUID, UserEntity> userMap,
            Map<UUID, UserStats> statsMap) {
        UserEntity buyer = userMap.get(t.getBuyerId());
        UserEntity helper = userMap.get(t.getAssignedHelperId());
        UserStats buyerStats = statsMap.get(t.getBuyerId());
        UserStats helperStats = statsMap.get(t.getAssignedHelperId());

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

        return new TaskResponse(
                t.getId(),
                t.getBuyerId(),
                buyerPhone,
                buyerName,
                t.getTitle(),
                t.getDescription(),
                t.getUrgency(),
                t.getTimeMinutes(),
                t.getBudgetPaise(),
                t.getLat(),
                t.getLng(),
                t.getAddressText(),
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
                t.getCreatedAt());
    }

    private UserStats fetchUserStats(UUID userId) {
        // This is still N queries if unique user count is high, but much better than N
        // * 6.
        // Ideally these would be aggregate queries using GROUP BY or cached.
        Long count = tasks.countByAssignedHelperIdAndStatus(userId, TaskStatus.COMPLETED);
        if (count == 0) {
            count = tasks.countByBuyerIdAndStatus(userId, TaskStatus.COMPLETED);
        }
        Double helperAvg = tasks.avgBuyerRatingForHelper(userId);
        Double buyerAvg = tasks.avgHelperRatingForBuyer(userId);
        return new UserStats(count, helperAvg != null ? helperAvg : buyerAvg);
    }

    private record UserStats(Long completedCount, Double avgRating) {
    }
}
