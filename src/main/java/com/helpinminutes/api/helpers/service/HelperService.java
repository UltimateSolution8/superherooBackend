package com.helpinminutes.api.helpers.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.dto.HelperIdCardResponse;
import com.helpinminutes.api.helpers.dto.HelperProfileResponse;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.common.GeoUtils;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HelperService {
  private final HelperProfileRepository profiles;
  private final HelperPresenceService presence;
  private final SupabaseStorageService storage;
  private final UserRepository users;
  private final TaskRepository tasks;
  private final MatchingService matching;

  public HelperService(
      HelperProfileRepository profiles,
      HelperPresenceService presence,
      SupabaseStorageService storage,
      UserRepository users,
      TaskRepository tasks,
      MatchingService matching) {
    this.profiles = profiles;
    this.presence = presence;
    this.storage = storage;
    this.users = users;
    this.tasks = tasks;
    this.matching = matching;
  }

  public void setOnline(UUID helperId, double lat, double lng) {
    HelperProfileEntity profile = profiles.findById(helperId)
        .orElseThrow(() -> new ForbiddenException("Not a helper"));

    if (profile.getKycStatus() != HelperKycStatus.APPROVED) {
      throw new ForbiddenException("Helper is not KYC approved");
    }

    presence.setOnline(helperId, lat, lng);

    try {
      java.util.List<TaskEntity> searching = tasks.findTop100ByStatusOrderByCreatedAtDesc(TaskStatus.SEARCHING);
      for (TaskEntity task : searching) {
        if (task.getAssignedHelperId() == null) {
          double dist = GeoUtils.distanceMeters(task.getLat(), task.getLng(), lat, lng);
          if (dist <= 3000d) {
            matching.dispatchOffers(task);
          }
        }
      }
    } catch (Exception ignored) {
    }
  }

  public void setOffline(UUID helperId) {
    profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    presence.setOffline(helperId);
  }

  public HelperProfileResponse getProfile(UUID helperId) {
    HelperProfileEntity p = profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    return toResponse(p);
  }

  public HelperIdCardResponse getIdCard(UUID helperId) {
    HelperProfileEntity profile = profiles.findById(helperId).orElseThrow(() -> new NotFoundException("Helper profile not found"));
    UserEntity user = users.findById(helperId).orElseThrow(() -> new NotFoundException("Helper not found"));
    String fullName = profile.getKycFullName();
    if (fullName == null || fullName.isBlank()) {
      fullName = user.getDisplayName();
    }
    if (fullName == null || fullName.isBlank()) {
      fullName = user.getPhone();
    }
    String badgeId = buildBadgeId(fullName, user.getPhone(), profile.getKycIdNumber(), helperId);
    String idNumberMasked = maskId(profile.getKycIdNumber());
    Instant issuedAt = profile.getKycSubmittedAt() == null ? user.getCreatedAt() : profile.getKycSubmittedAt();
    return new HelperIdCardResponse(
        helperId,
        badgeId,
        fullName,
        user.getPhone(),
        profile.getKycStatus() == null ? "PENDING" : profile.getKycStatus().name(),
        idNumberMasked,
        profile.getKycSelfieUrl(),
        profile.getKycDocFrontUrl(),
        profile.getKycDocBackUrl(),
        issuedAt);
  }

  @Transactional
  public HelperProfileResponse submitKyc(
      UUID helperId,
      String fullName,
      String docType,
      String idNumber,
      MultipartFile idFront,
      MultipartFile idBack,
      MultipartFile selfie) {
    if (fullName == null || fullName.isBlank()) {
      throw new BadRequestException("fullName is required");
    }
    if (idNumber == null || idNumber.isBlank()) {
      throw new BadRequestException("idNumber is required");
    }
    String normalizedDocType = normalizeDocType(docType);
    String normalizedIdNumber = normalizeAndValidateIdNumber(idNumber, normalizedDocType);
    if ("AADHAAR".equals(normalizedDocType) && (idBack == null || idBack.isEmpty())) {
      throw new BadRequestException("Aadhaar back image is required");
    }

    HelperProfileEntity p = profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    String frontUrl = storage.uploadHelperKycDoc(helperId, "id-front", idFront);
    String backUrl = idBack == null || idBack.isEmpty() ? null : storage.uploadHelperKycDoc(helperId, "id-back", idBack);
    String selfieUrl = storage.uploadHelperKycDoc(helperId, "selfie", selfie);

    p.setKycFullName(fullName.trim());
    p.setKycIdNumber(normalizedIdNumber);
    p.setKycDocFrontUrl(frontUrl);
    p.setKycDocBackUrl(backUrl);
    p.setKycSelfieUrl(selfieUrl);
    p.setKycSubmittedAt(Instant.now());
    p.setKycStatus(HelperKycStatus.PENDING);
    p.setKycRejectionReason(null);
    profiles.save(p);

    return toResponse(p);
  }

  private HelperProfileResponse toResponse(HelperProfileEntity p) {
    Integer position = queuePosition(p);
    return new HelperProfileResponse(
        p.getKycStatus(),
        p.getKycRejectionReason(),
        p.getKycFullName(),
        p.getKycIdNumber(),
        p.getKycDocFrontUrl(),
        p.getKycDocBackUrl(),
        p.getKycSelfieUrl(),
        p.getKycSubmittedAt(),
        buildKycTokenNumber(p),
        position,
        estimatedWaitMinutes(position));
  }

  private static String buildKycTokenNumber(HelperProfileEntity p) {
    if (p == null || p.getCreatedAt() == null) return null;
    long suffix = Math.abs(p.getUserId().hashCode()) % 900 + 100;
    return "KYC-" + suffix;
  }

  private static Integer estimatedWaitMinutes(Integer position) {
    return position == null ? null : Math.max(3, position * 3);
  }

  private Integer queuePosition(HelperProfileEntity p) {
    if (p == null || p.getKycStatus() != HelperKycStatus.PENDING) return null;
    var pending = profiles.findAllByKycStatusOrderByCreatedAtAsc(HelperKycStatus.PENDING);
    for (int i = 0; i < pending.size(); i++) {
      if (pending.get(i).getUserId().equals(p.getUserId())) {
        return i + 1;
      }
    }
    return null;
  }

  private static String maskId(String id) {
    if (id == null || id.isBlank()) return null;
    String raw = id.trim();
    if (raw.length() <= 4) return raw;
    return "XXXXXX" + raw.substring(raw.length() - 4);
  }

  private static String buildBadgeId(String fullName, String phone, String idNumber, UUID fallbackId) {
    String name = fullName == null ? "" : fullName.replaceAll("[^A-Za-z]", "").toUpperCase();
    String namePart = (name + "XXX").substring(0, 3);
    String phoneDigits = phone == null ? "" : phone.replaceAll("\\D", "");
    String idDigits = idNumber == null ? "" : idNumber.replaceAll("[^A-Z0-9]", "").toUpperCase();
    String phonePart = last4OrFallback(phoneDigits, fallbackId.toString().replaceAll("\\D", ""));
    String idPart = last4OrFallback(idDigits, fallbackId.toString().replaceAll("[^A-Fa-f0-9]", "").toUpperCase());
    return "SHO-" + namePart + "-" + phonePart + "-" + idPart;
  }

  private static String last4OrFallback(String value, String fallback) {
    String source = value == null || value.length() < 4 ? fallback : value;
    if (source == null || source.isBlank()) return "0000";
    return source.length() <= 4 ? String.format("%4s", source).replace(' ', '0') : source.substring(source.length() - 4);
  }

  private static String normalizeDocType(String docType) {
    if (docType == null || docType.isBlank()) return "AADHAAR";
    String value = docType.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    if ("DL".equals(value)) return "DRIVING_LICENSE";
    return value;
  }

  private static String normalizeAndValidateIdNumber(String idNumber, String docType) {
    String value = idNumber == null ? "" : idNumber.trim().toUpperCase();
    if ("AADHAAR".equals(docType)) {
      value = value.replaceAll("\\D", "");
      if (!value.matches("\\d{12}")) throw new BadRequestException("Aadhaar must be 12 digits");
      return value;
    }
    if ("PAN".equals(docType)) {
      value = value.replaceAll("[^A-Z0-9]", "");
      if (!value.matches("[A-Z]{5}[0-9]{4}[A-Z]")) throw new BadRequestException("PAN must match ABCDE1234F");
      return value;
    }
    if ("PASSPORT".equals(docType)) {
      value = value.replaceAll("[^A-Z0-9]", "");
      if (!value.matches("[A-Z][0-9]{7}")) throw new BadRequestException("Passport number is invalid");
      return value;
    }
    if ("DRIVING_LICENSE".equals(docType)) {
      value = value.replaceAll("[^A-Z0-9]", "");
      if (!value.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{8,14}")) throw new BadRequestException("Driving licence number is invalid");
      return value;
    }
    if ("RATION_CARD".equals(docType)) {
      value = value.replaceAll("[^A-Z0-9]", "");
      if (!value.matches("[A-Z0-9]{8,20}")) throw new BadRequestException("Ration card number is invalid");
      return value;
    }
    if (value.length() < 4 || value.length() > 30) throw new BadRequestException("Document number is invalid");
    return value;
  }
}
