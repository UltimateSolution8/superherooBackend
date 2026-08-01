package com.helpinminutes.api.helpers.service;

import com.helpinminutes.api.common.InputValidators;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.dto.PublicPartnerKycResponse;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.PublicPartnerKycSubmissionEntity;
import com.helpinminutes.api.helpers.repo.PublicPartnerKycSubmissionRepository;
import com.helpinminutes.api.storage.SupabaseStorageService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PublicPartnerKycService {
  private static final long MAX_IMAGE_BYTES = 8L * 1024L * 1024L;
  private static final int CONTACT_SUBMISSIONS_PER_DAY = 3;

  private final PublicPartnerKycSubmissionRepository submissions;
  private final SupabaseStorageService storage;

  public PublicPartnerKycService(
      PublicPartnerKycSubmissionRepository submissions,
      SupabaseStorageService storage) {
    this.submissions = submissions;
    this.storage = storage;
  }

  @Transactional
  public PublicPartnerKycResponse submit(
      String fullName,
      String phone,
      String email,
      String docType,
      String idNumber,
      MultipartFile idFront,
      MultipartFile idBack,
      MultipartFile selfie,
      String accountHolderName,
      String bankName,
      String bankAccountLast4,
      String ifscCode,
      String upiIdMasked) {
    String legalName = requireText(fullName, "Full name is required", 120);
    String normalizedPhone = InputValidators.normalizeIndianPhoneOrNull(phone);
    if (normalizedPhone == null) throw new BadRequestException("Phone number is required");
    String normalizedEmail = InputValidators.requireEmail(email);
    enforceContactRateLimit(normalizedPhone, normalizedEmail);

    String normalizedDocType = normalizeDocType(docType);
    String normalizedIdNumber = normalizeAndValidateIdNumber(idNumber, normalizedDocType);
    validateImage(idFront, "ID front photo");
    if ("AADHAAR".equals(normalizedDocType)) validateImage(idBack, "Aadhaar back photo");
    validateImage(selfie, "Partner selfie");

    UUID submissionId = UUID.randomUUID();
    String frontUrl = storage.uploadHelperKycDoc(submissionId, "public-id-front", idFront);
    String backUrl = idBack == null || idBack.isEmpty() ? null : storage.uploadHelperKycDoc(submissionId, "public-id-back", idBack);
    String selfieUrl = storage.uploadHelperKycDoc(submissionId, "public-selfie", selfie);

    PublicPartnerKycSubmissionEntity entity = new PublicPartnerKycSubmissionEntity();
    entity.setId(submissionId);
    entity.setSource(PublicPartnerKycSubmissionEntity.SOURCE_WEB_PUBLIC_KYC);
    entity.setStatus(HelperKycStatus.PENDING);
    entity.setFullName(legalName);
    entity.setPhone(normalizedPhone);
    entity.setEmail(normalizedEmail);
    entity.setDocType(normalizedDocType);
    entity.setIdNumber(normalizedIdNumber);
    entity.setDocFrontUrl(frontUrl);
    entity.setDocBackUrl(backUrl);
    entity.setSelfieUrl(selfieUrl);
    entity.setAccountHolderName(trimToNull(accountHolderName, 160));
    entity.setBankName(trimToNull(bankName, 160));
    entity.setBankAccountLast4(normalizeLast4(bankAccountLast4));
    entity.setIfscCode(normalizeIfscOrNull(ifscCode));
    entity.setUpiIdMasked(trimToNull(upiIdMasked, 160));
    PublicPartnerKycSubmissionEntity saved = submissions.save(entity);
    return toResponse(saved);
  }

  public PublicPartnerKycResponse toResponse(PublicPartnerKycSubmissionEntity entity) {
    return new PublicPartnerKycResponse(
        entity.getId(),
        entity.getStatus(),
        referenceId(entity.getId()),
        entity.getCreatedAt());
  }

  @Transactional
  public void approve(UUID submissionId, UUID adminId) {
    PublicPartnerKycSubmissionEntity entity = submissions.findById(submissionId)
        .orElseThrow(() -> new NotFoundException("Public KYC submission not found"));
    entity.setStatus(HelperKycStatus.APPROVED);
    entity.setRejectionReason(null);
    entity.setReviewedAt(Instant.now());
    entity.setReviewedByAdminId(adminId);
    submissions.save(entity);
  }

  @Transactional
  public void reject(UUID submissionId, UUID adminId, String reason) {
    PublicPartnerKycSubmissionEntity entity = submissions.findById(submissionId)
        .orElseThrow(() -> new NotFoundException("Public KYC submission not found"));
    entity.setStatus(HelperKycStatus.REJECTED);
    entity.setRejectionReason(reason == null || reason.isBlank() ? "Incomplete KYC" : reason.trim());
    entity.setReviewedAt(Instant.now());
    entity.setReviewedByAdminId(adminId);
    submissions.save(entity);
  }

  public static String referenceId(UUID id) {
    if (id == null) return "PKYC-PENDING";
    String compact = id.toString().replace("-", "").toUpperCase(Locale.ROOT);
    return "PKYC-" + compact.substring(0, Math.min(8, compact.length()));
  }

  private void enforceContactRateLimit(String phone, String email) {
    Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
    if (submissions.countByPhoneAndCreatedAtAfter(phone, since) >= CONTACT_SUBMISSIONS_PER_DAY
        || submissions.countByEmailAndCreatedAtAfter(email, since) >= CONTACT_SUBMISSIONS_PER_DAY) {
      throw new BadRequestException("Too many KYC submissions. Please wait and try again.");
    }
  }

  private static void validateImage(MultipartFile file, String label) {
    if (file == null || file.isEmpty()) {
      throw new BadRequestException(label + " is required");
    }
    if (file.getSize() > MAX_IMAGE_BYTES) {
      throw new BadRequestException(label + " must be smaller than 8 MB");
    }
    String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    if (!contentType.startsWith("image/")) {
      throw new BadRequestException(label + " must be an image");
    }
  }

  private static String requireText(String value, String message, int maxLength) {
    String trimmed = trimToNull(value, maxLength);
    if (trimmed == null) throw new BadRequestException(message);
    if (trimmed.length() < 3) throw new BadRequestException(message);
    return trimmed;
  }

  private static String trimToNull(String value, int maxLength) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
  }

  private static String normalizeLast4(String value) {
    String digits = value == null ? "" : value.replaceAll("\\D", "");
    if (digits.isBlank()) return null;
    if (digits.length() != 4) throw new BadRequestException("Bank account ending must be last 4 digits");
    return digits;
  }

  private static String normalizeIfscOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw new BadRequestException("IFSC code is invalid");
    return normalized;
  }

  private static String normalizeDocType(String docType) {
    if (docType == null || docType.isBlank()) return "AADHAAR";
    String value = docType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    if ("DL".equals(value)) return "DRIVING_LICENSE";
    return value;
  }

  private static String normalizeAndValidateIdNumber(String idNumber, String docType) {
    String value = idNumber == null ? "" : idNumber.trim().toUpperCase(Locale.ROOT);
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
