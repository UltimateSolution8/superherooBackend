package com.helpinminutes.api.admin.service;

import com.helpinminutes.api.admin.dto.AdminCreateUserRequest;
import com.helpinminutes.api.admin.dto.AdminBulkOperationFailure;
import com.helpinminutes.api.admin.dto.AdminBulkOperationResponse;
import com.helpinminutes.api.admin.dto.AdminManagedUserResponse;
import com.helpinminutes.api.admin.dto.AdminSummaryResponse;
import com.helpinminutes.api.admin.dto.AdminUpdateUserRequest;
import com.helpinminutes.api.admin.dto.PendingHelperResponse;
import com.helpinminutes.api.common.InputValidators;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
  private final HelperProfileRepository helperProfiles;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final TaskRepository tasks;
  private final NotificationQueueService notificationQueue;

  public AdminService(
      HelperProfileRepository helperProfiles,
      UserRepository users,
      PasswordEncoder passwordEncoder,
      TaskRepository tasks,
      NotificationQueueService notificationQueue) {
    this.helperProfiles = helperProfiles;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.tasks = tasks;
    this.notificationQueue = notificationQueue;
  }

  public List<PendingHelperResponse> listPendingHelpers() {
    List<HelperProfileEntity> pending = helperProfiles.findAllByKycStatusOrderByCreatedAtAsc(HelperKycStatus.PENDING);
    if (pending.isEmpty()) {
      return List.of();
    }
    List<UUID> userIds = pending.stream().map(HelperProfileEntity::getUserId).toList();
    Map<UUID, String> phoneByUserId = new HashMap<>();
    users.findAllById(userIds).forEach(u -> phoneByUserId.put(u.getId(), u.getPhone()));
    return pending.stream()
        .map(hp -> {
          String phone = phoneByUserId.get(hp.getUserId());
          return new PendingHelperResponse(
              hp.getUserId(),
              phone,
              hp.getKycStatus(),
              hp.getKycFullName(),
              hp.getKycIdNumber(),
              hp.getKycDocFrontUrl(),
              hp.getKycDocBackUrl(),
              hp.getKycSelfieUrl(),
              hp.getKycSubmittedAt(),
              hp.getCreatedAt());
        })
        .toList();
  }

  public AdminSummaryResponse summary() {
    long pendingHelpers = helperProfiles.countByKycStatus(HelperKycStatus.PENDING);
    long searching = tasks.countByStatus(TaskStatus.SEARCHING);
    long assigned = tasks.countByStatus(TaskStatus.ASSIGNED);
    long arrived = tasks.countByStatus(TaskStatus.ARRIVED);
    long started = tasks.countByStatus(TaskStatus.STARTED);
    long completed = tasks.countByStatus(TaskStatus.COMPLETED);
    long revenue = tasks.sumBudgetPaiseByStatus(TaskStatus.COMPLETED);
    return new AdminSummaryResponse(
        pendingHelpers,
        searching,
        assigned,
        arrived,
        started,
        completed,
        revenue);
  }

  @Transactional
  public void approveHelper(UUID helperId) {
    HelperProfileEntity hp = helperProfiles.findById(helperId)
        .orElseThrow(() -> new NotFoundException("Helper not found"));
    hp.setKycStatus(HelperKycStatus.APPROVED);
    hp.setKycRejectionReason(null);
    helperProfiles.save(hp);
    notificationQueue.enqueueKycApproved(helperId);
  }

  @Transactional
  public void rejectHelper(UUID helperId, String reason) {
    HelperProfileEntity hp = helperProfiles.findById(helperId)
        .orElseThrow(() -> new NotFoundException("Helper not found"));
    hp.setKycStatus(HelperKycStatus.REJECTED);
    hp.setKycRejectionReason(reason);
    helperProfiles.save(hp);
  }

  @Transactional
  public void reopenHelperKyc(UUID helperId) {
    HelperProfileEntity hp = helperProfiles.findById(helperId)
        .orElseThrow(() -> new NotFoundException("Helper not found"));
    hp.setKycStatus(HelperKycStatus.PENDING);
    hp.setKycRejectionReason(null);
    helperProfiles.save(hp);
  }

  @Transactional
  public void verifyHelperKyc(UUID helperId) {
    approveHelper(helperId);
  }

  @Transactional
  public AdminBulkOperationResponse bulkUpdateUsers(UserRole role, List<UUID> userIds, String statusRaw) {
    if (userIds == null || userIds.isEmpty()) {
      throw new BadRequestException("At least one user id is required");
    }
    UserStatus nextStatus = parseStatusOrDefault(statusRaw, null);
    if (nextStatus == null) {
      throw new BadRequestException("Invalid status");
    }
    List<AdminBulkOperationFailure> failures = new java.util.ArrayList<>();
    int success = 0;
    for (UUID userId : new LinkedHashSet<>(userIds)) {
      if (userId == null) {
        failures.add(new AdminBulkOperationFailure("missing-user-id", "Missing user id"));
        continue;
      }
      try {
        UserEntity u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (u.getRole() != role) {
          throw new BadRequestException("User role mismatch");
        }
        u.setStatus(nextStatus);
        users.save(u);
        success++;
      } catch (Exception ex) {
        failures.add(new AdminBulkOperationFailure(userId.toString(), sanitizeFailureMessage(ex, "Update failed")));
      }
    }
    return new AdminBulkOperationResponse(userIds.size(), success, failures.size(), failures);
  }

  @Transactional
  public AdminBulkOperationResponse bulkSetBuyerCsvAccess(List<UUID> buyerIds, boolean enabled) {
    if (buyerIds == null || buyerIds.isEmpty()) {
      throw new BadRequestException("At least one buyer id is required");
    }

    List<AdminBulkOperationFailure> failures = new java.util.ArrayList<>();
    int success = 0;
    for (UUID buyerId : new LinkedHashSet<>(buyerIds)) {
      if (buyerId == null) {
        failures.add(new AdminBulkOperationFailure("missing-buyer-id", "Missing buyer id"));
        continue;
      }
      try {
        UserEntity u = users.findById(buyerId).orElseThrow(() -> new NotFoundException("Buyer not found"));
        if (u.getRole() != UserRole.BUYER) {
          throw new BadRequestException("User role mismatch");
        }
        u.setBulkCsvEnabled(enabled);
        users.save(u);
        success++;
      } catch (Exception ex) {
        failures.add(new AdminBulkOperationFailure(buyerId.toString(), sanitizeFailureMessage(ex, "Update failed")));
      }
    }
    return new AdminBulkOperationResponse(buyerIds.size(), success, failures.size(), failures);
  }

  @Transactional
  public AdminBulkOperationResponse bulkHelperKycAction(List<UUID> helperIds, String actionRaw, String reason) {
    if (helperIds == null || helperIds.isEmpty()) {
      throw new BadRequestException("At least one helper id is required");
    }
    String action = actionRaw == null ? "" : actionRaw.trim().toUpperCase(Locale.ROOT);
    if (!"APPROVE".equals(action) && !"REJECT".equals(action) && !"REOPEN".equals(action)) {
      throw new BadRequestException("Invalid action");
    }

    List<AdminBulkOperationFailure> failures = new java.util.ArrayList<>();
    int success = 0;
    for (UUID helperId : new LinkedHashSet<>(helperIds)) {
      if (helperId == null) {
        failures.add(new AdminBulkOperationFailure("missing-helper-id", "Missing helper id"));
        continue;
      }
      try {
        switch (action) {
          case "APPROVE" -> approveHelper(helperId);
          case "REJECT" -> rejectHelper(helperId, reason == null || reason.isBlank() ? "Rejected in bulk action" : reason.trim());
          case "REOPEN" -> reopenHelperKyc(helperId);
          default -> throw new BadRequestException("Invalid action");
        }
        success++;
      } catch (Exception ex) {
        failures.add(new AdminBulkOperationFailure(helperId.toString(), sanitizeFailureMessage(ex, "Action failed")));
      }
    }
    return new AdminBulkOperationResponse(helperIds.size(), success, failures.size(), failures);
  }

  public List<AdminManagedUserResponse> listUsersByRole(UserRole role) {
    List<UserEntity> usersByRole = users.findTop200ByRoleOrderByCreatedAtDesc(role);
    if (usersByRole.isEmpty()) {
      return List.of();
    }
    Map<UUID, HelperProfileEntity> helperProfileByUserId = Collections.emptyMap();
    if (role == UserRole.HELPER) {
      List<UUID> helperIds = usersByRole.stream().map(UserEntity::getId).toList();
      Map<UUID, HelperProfileEntity> map = new HashMap<>();
      helperProfiles.findAllById(helperIds).forEach(hp -> map.put(hp.getUserId(), hp));
      helperProfileByUserId = map;
    }
    Map<UUID, HelperProfileEntity> finalHelperProfileByUserId = helperProfileByUserId;
    return usersByRole.stream()
        .map(u -> toManagedResponse(u, finalHelperProfileByUserId.get(u.getId())))
        .toList();
  }

  @Transactional
  public AdminManagedUserResponse createUser(UserRole role, AdminCreateUserRequest req) {
    String phone = InputValidators.normalizeIndianPhoneOrNull(req.phone());
    String email = InputValidators.normalizeEmailOrNull(req.email());
    String displayName = trimOrNull(req.displayName());
    String password = trimOrNull(req.password());

    if (phone == null && email == null) {
      throw new BadRequestException("phone or email required");
    }
    if (phone != null && users.findByPhoneAndRole(phone, role).isPresent()) {
      throw new BadRequestException("phone already in use");
    }
    if (email != null && users.findByEmail(email).isPresent()) {
      throw new BadRequestException("email already in use");
    }

    UserEntity u = new UserEntity();
    u.setRole(role);
    u.setStatus(parseStatusOrDefault(req.status(), UserStatus.ACTIVE));
    u.setPhone(phone);
    u.setEmail(email);
    u.setDisplayName(displayName);
    if (password != null) {
      u.setPasswordHash(passwordEncoder.encode(password));
    }
    users.save(u);

    if (role == UserRole.HELPER) {
      helperProfiles.findById(u.getId()).orElseGet(() -> {
        HelperProfileEntity hp = new HelperProfileEntity();
        hp.setUserId(u.getId());
        hp.setKycStatus(HelperKycStatus.PENDING);
        return helperProfiles.save(hp);
      });
    }
    return toManagedResponse(u, role == UserRole.HELPER ? helperProfiles.findById(u.getId()).orElse(null) : null);
  }

  @Transactional
  public AdminManagedUserResponse updateUser(UUID userId, UserRole role, AdminUpdateUserRequest req) {
    UserEntity u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (u.getRole() != role) {
      throw new BadRequestException("User role mismatch");
    }

    String phone = InputValidators.normalizeIndianPhoneOrNull(req.phone());
    String email = InputValidators.normalizeEmailOrNull(req.email());

    if (phone != null && !phone.equals(u.getPhone())) {
      users.findByPhoneAndRole(phone, role).ifPresent(existing -> {
        if (!existing.getId().equals(u.getId())) {
          throw new BadRequestException("phone already in use");
        }
      });
      u.setPhone(phone);
    } else if (phone == null) {
      u.setPhone(null);
    }

    String currentEmail = u.getEmail();
    if (email != null && (currentEmail == null || !email.equalsIgnoreCase(currentEmail))) {
      users.findByEmail(email).ifPresent(existing -> {
        if (!existing.getId().equals(u.getId())) {
          throw new BadRequestException("email already in use");
        }
      });
      u.setEmail(email);
    } else if (email == null) {
      u.setEmail(null);
    }

    u.setDisplayName(trimOrNull(req.displayName()));
    if (req.status() != null && !req.status().isBlank()) {
      u.setStatus(parseStatusOrDefault(req.status(), u.getStatus()));
    }
    users.save(u);
    return toManagedResponse(u, role == UserRole.HELPER ? helperProfiles.findById(u.getId()).orElse(null) : null);
  }

  @Transactional
  public void deleteUser(UUID userId, UserRole role) {
    UserEntity u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (u.getRole() != role) {
      throw new BadRequestException("User role mismatch");
    }
    // Soft-delete to avoid FK constraint failures on tasks/payments.
    u.setStatus(UserStatus.BLOCKED);
    u.setPhone(null);
    u.setEmail(null);
    u.setDisplayName("Deleted user");
    users.save(u);
  }

  private AdminManagedUserResponse toManagedResponse(UserEntity u, HelperProfileEntity hp) {
    return new AdminManagedUserResponse(
        u.getId(),
        u.getRole(),
        u.getStatus(),
        u.getPhone(),
        u.getEmail(),
        u.getDisplayName(),
        u.getCreatedAt(),
        u.isBulkCsvEnabled(),
        hp == null ? null : hp.getKycStatus(),
        hp == null ? null : hp.getKycFullName(),
        hp == null ? null : hp.getKycIdNumber(),
        hp == null ? null : hp.getKycDocFrontUrl(),
        hp == null ? null : hp.getKycDocBackUrl(),
        hp == null ? null : hp.getKycSelfieUrl(),
        hp == null ? null : hp.getKycSubmittedAt());
  }

  private static UserStatus parseStatusOrDefault(String raw, UserStatus fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return UserStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (Exception e) {
      throw new BadRequestException("Invalid status");
    }
  }

  private static String sanitizeFailureMessage(Exception ex, String fallback) {
    if (ex == null || ex.getMessage() == null) return fallback;
    String msg = ex.getMessage().trim();
    if (msg.isBlank()) return fallback;
    if (msg.length() > 180) return fallback;
    return msg;
  }

  private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isBlank() ? null : t;
  }
}
