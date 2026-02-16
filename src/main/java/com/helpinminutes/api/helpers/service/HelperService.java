package com.helpinminutes.api.helpers.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.helpers.dto.HelperProfileResponse;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.storage.SupabaseStorageService;
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

  public HelperService(
      HelperProfileRepository profiles,
      HelperPresenceService presence,
      SupabaseStorageService storage) {
    this.profiles = profiles;
    this.presence = presence;
    this.storage = storage;
  }

  public void setOnline(UUID helperId, double lat, double lng) {
    HelperProfileEntity profile = profiles.findById(helperId)
        .orElseThrow(() -> new ForbiddenException("Not a helper"));

    if (profile.getKycStatus() != HelperKycStatus.APPROVED) {
      throw new ForbiddenException("Helper is not KYC approved");
    }

    presence.setOnline(helperId, lat, lng);
  }

  public void setOffline(UUID helperId) {
    profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    presence.setOffline(helperId);
  }

  public HelperProfileResponse getProfile(UUID helperId) {
    HelperProfileEntity p = profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    return toResponse(p);
  }

  @Transactional
  public HelperProfileResponse submitKyc(
      UUID helperId,
      String fullName,
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

    HelperProfileEntity p = profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    String frontUrl = storage.uploadHelperKycDoc(helperId, "id-front", idFront);
    String backUrl = storage.uploadHelperKycDoc(helperId, "id-back", idBack);
    String selfieUrl = storage.uploadHelperKycDoc(helperId, "selfie", selfie);

    p.setKycFullName(fullName.trim());
    p.setKycIdNumber(idNumber.trim());
    p.setKycDocFrontUrl(frontUrl);
    p.setKycDocBackUrl(backUrl);
    p.setKycSelfieUrl(selfieUrl);
    p.setKycSubmittedAt(Instant.now());
    p.setKycStatus(HelperKycStatus.PENDING);
    p.setKycRejectionReason(null);
    profiles.save(p);

    return toResponse(p);
  }

  private static HelperProfileResponse toResponse(HelperProfileEntity p) {
    return new HelperProfileResponse(
        p.getKycStatus(),
        p.getKycRejectionReason(),
        p.getKycFullName(),
        p.getKycIdNumber(),
        p.getKycDocFrontUrl(),
        p.getKycDocBackUrl(),
        p.getKycSelfieUrl(),
        p.getKycSubmittedAt());
  }
}
