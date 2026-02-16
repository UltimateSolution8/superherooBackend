package com.helpinminutes.api.admin.service;

import com.helpinminutes.api.admin.dto.AdminCreateUserRequest;
import com.helpinminutes.api.admin.dto.AdminManagedUserResponse;
import com.helpinminutes.api.admin.dto.AdminUpdateUserRequest;
import com.helpinminutes.api.admin.dto.PendingHelperResponse;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
  private final HelperProfileRepository helperProfiles;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;

  public AdminService(
      HelperProfileRepository helperProfiles,
      UserRepository users,
      PasswordEncoder passwordEncoder) {
    this.helperProfiles = helperProfiles;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
  }

  public List<PendingHelperResponse> listPendingHelpers() {
    return helperProfiles.findAllByKycStatusOrderByCreatedAtAsc(HelperKycStatus.PENDING).stream()
        .map(hp -> {
          String phone = users.findById(hp.getUserId()).map(u -> u.getPhone()).orElse(null);
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

  @Transactional
  public void approveHelper(UUID helperId) {
    HelperProfileEntity hp = helperProfiles.findById(helperId)
        .orElseThrow(() -> new NotFoundException("Helper not found"));
    hp.setKycStatus(HelperKycStatus.APPROVED);
    hp.setKycRejectionReason(null);
    helperProfiles.save(hp);
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
  public void verifyHelperKyc(UUID helperId) {
    approveHelper(helperId);
  }

  public List<AdminManagedUserResponse> listUsersByRole(UserRole role) {
    return users.findTop200ByRoleOrderByCreatedAtDesc(role).stream()
        .map(this::toManagedResponse)
        .toList();
  }

  @Transactional
  public AdminManagedUserResponse createUser(UserRole role, AdminCreateUserRequest req) {
    String phone = trimOrNull(req.phone());
    String email = normalizeEmailOrNull(req.email());
    String displayName = trimOrNull(req.displayName());
    String password = trimOrNull(req.password());

    if (phone == null && email == null) {
      throw new BadRequestException("phone or email required");
    }
    if (phone != null && users.findByPhone(phone).isPresent()) {
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
    return toManagedResponse(u);
  }

  @Transactional
  public AdminManagedUserResponse updateUser(UUID userId, UserRole role, AdminUpdateUserRequest req) {
    UserEntity u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (u.getRole() != role) {
      throw new BadRequestException("User role mismatch");
    }

    String phone = trimOrNull(req.phone());
    String email = normalizeEmailOrNull(req.email());

    if (phone != null && !phone.equals(u.getPhone())) {
      users.findByPhone(phone).ifPresent(existing -> {
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
    return toManagedResponse(u);
  }

  @Transactional
  public void deleteUser(UUID userId, UserRole role) {
    UserEntity u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (u.getRole() != role) {
      throw new BadRequestException("User role mismatch");
    }
    users.deleteById(userId);
  }

  private AdminManagedUserResponse toManagedResponse(UserEntity u) {
    HelperProfileEntity hp = u.getRole() == UserRole.HELPER ? helperProfiles.findById(u.getId()).orElse(null) : null;
    return new AdminManagedUserResponse(
        u.getId(),
        u.getRole(),
        u.getStatus(),
        u.getPhone(),
        u.getEmail(),
        u.getDisplayName(),
        u.getCreatedAt(),
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

  private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isBlank() ? null : t;
  }

  private static String normalizeEmailOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    return s.trim().toLowerCase(Locale.ROOT);
  }
}
