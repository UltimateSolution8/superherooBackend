package com.helpinminutes.api.helpers.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.dto.HelperIdCardResponse;
import com.helpinminutes.api.helpers.dto.HelperBankDetailsResponse;
import com.helpinminutes.api.helpers.dto.HelperPayoutAccountRequest;
import com.helpinminutes.api.helpers.dto.PayoutAccountUpdateRequest;
import com.helpinminutes.api.helpers.dto.HelperProfileResponse;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.common.GeoUtils;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HelperService {

  /** How long a helper's go-online dispatch sweep is suppressed after one runs. */
  private static final java.time.Duration GO_ONLINE_DISPATCH_COOLDOWN =
      java.time.Duration.ofSeconds(60);

  /**
   * Rows fetched from the bounding box before the exact-radius filter. Larger
   * than the dispatch limit because the box covers more area than the circle.
   */
  private static final int GO_ONLINE_CANDIDATE_PAGE_SIZE = 50;

  private final HelperProfileRepository profiles;
  private final HelperPresenceService presence;
  private final SupabaseStorageService storage;
  private final UserRepository users;
  private final TaskRepository tasks;
  private final NotificationQueueService notificationQueue;
  private final Executor realtimeDispatchExecutor;
  private final PayoutAccountService payoutAccountService;
  private final com.helpinminutes.api.config.AppProperties props;

  @org.springframework.beans.factory.annotation.Value("${GO_ONLINE_DISPATCH_LIMIT:15}")
  private int goOnlineDispatchLimit = 15;

  public HelperService(
      HelperProfileRepository profiles,
      HelperPresenceService presence,
      SupabaseStorageService storage,
      UserRepository users,
      TaskRepository tasks,
      NotificationQueueService notificationQueue,
      @Qualifier("realtimeDispatchExecutor") Executor realtimeDispatchExecutor,
      PayoutAccountService payoutAccountService,
      com.helpinminutes.api.config.AppProperties props) {
    this.profiles = profiles;
    this.presence = presence;
    this.storage = storage;
    this.users = users;
    this.tasks = tasks;
    this.notificationQueue = notificationQueue;
    this.realtimeDispatchExecutor = realtimeDispatchExecutor;
    this.payoutAccountService = payoutAccountService;
    this.props = props;
  }

  public void setOnline(UUID helperId, double lat, double lng) {
    if (!Double.isFinite(lat) || !Double.isFinite(lng)
        || !com.helpinminutes.api.common.ServiceArea.isWithinIndia(lat, lng)) {
      throw new com.helpinminutes.api.errors.BadRequestException(
          "Partner location is outside the supported region");
    }
    HelperProfileEntity profile = profiles.findById(helperId)
        .orElseThrow(() -> new ForbiddenException("Not a helper"));

    if (profile.getKycStatus() != HelperKycStatus.APPROVED) {
      throw new ForbiddenException("Helper is not KYC approved");
    }

    var update = presence.setOnline(helperId, lat, lng);

    // Only sweep on a genuine offline→online transition. The apps call this every
    // ~15s while online, and the sweep is the highest-fanout path in the system:
    // up to GO_ONLINE_DISPATCH_LIMIT full dispatch cycles, each with a row lock and
    // ~100 Redis commands. Running it per heartbeat also meant a billable SET NX on
    // every beat just to discover the cooldown had not elapsed.
    //
    // Nothing is missed by skipping it: a task created while the partner is already
    // online is dispatched to them by the normal matching flow, re-offered by the
    // cleanup job's escalating waves, and visible in their pull feed.
    if (update.wasOffline()) {
      realtimeDispatchExecutor.execute(() -> dispatchNearbySearchingTasks(helperId, lat, lng));
    }
  }

  /**
   * Re-offers nearby open tasks to a partner who has just come online.
   *
   * <p>This is the highest-fanout path in the system and needs two guards.
   *
   * <p>First, a per-helper Redis lock. Only reached on an offline→online transition
   * now (see {@link #setOnline}), but a partner rapidly toggling availability would
   * still re-run the whole sweep each time, and the lock is what makes that claim
   * atomic across instances.
   *
   * <p>Second, a hard cap. Every {@code dispatchOffers} takes a PESSIMISTIC_WRITE
   * row lock on the task and issues several Redis and database round trips, so an
   * unbounded loop over a 100-row page meant a single tap could trigger a hundred
   * full dispatch cycles. The cap is applied after the exact-radius filter, so it
   * bounds real work rather than candidates.
   */
  private void dispatchNearbySearchingTasks(UUID helperId, double lat, double lng) {
    try {
      if (!presence.tryAcquireGoOnlineDispatchLock(helperId, GO_ONLINE_DISPATCH_COOLDOWN)) {
        return;
      }
      // Same reach as the pull feed: a partner coming online should be told about
      // every open job they could actually take, not just the ones inside wave 0.
      double radiusMeters = props.matching().pullFeedRadiusMeters();
      GeoUtils.BoundingBox box = GeoUtils.boundingBox(lat, lng, radiusMeters);
      java.util.List<TaskEntity> searching = tasks.findAvailableInBounds(
          TaskStatus.SEARCHING,
          helperId,
          java.time.Instant.now(),
          box.minLat(),
          box.maxLat(),
          box.minLng(),
          box.maxLng(),
          org.springframework.data.domain.PageRequest.of(0, GO_ONLINE_CANDIDATE_PAGE_SIZE));
      int dispatched = 0;
      for (TaskEntity task : searching) {
        if (dispatched >= goOnlineDispatchLimit) {
          break;
        }
        if (GeoUtils.distanceMeters(task.getLat(), task.getLng(), lat, lng) <= radiusMeters) {
          notificationQueue.enqueueMatchingDispatch(task);
          dispatched++;
        }
      }
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(HelperService.class)
          .warn("Could not refresh nearby offers for helper {}: {}", helperId, e.getMessage());
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

  public HelperBankDetailsResponse getPayoutAccount(UUID helperId) {
    profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    return payoutAccountService.getCurrent(helperId);
  }

  @Transactional
  public HelperBankDetailsResponse savePayoutAccount(UUID helperId, PayoutAccountUpdateRequest req, String ipAddress) {
    profiles.findById(helperId).orElseThrow(() -> new ForbiddenException("Not a helper"));
    return payoutAccountService.replace(helperId, com.helpinminutes.api.users.model.UserRole.HELPER, req, ipAddress);
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
      MultipartFile selfie,
      String accountHolderName,
      String bankAccountNumber,
      String ifscCode) {
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
    PayoutAccountService.PreparedAccount preparedBank = null;
    if (!payoutAccountService.hasSecureCurrent(helperId)) {
      preparedBank = payoutAccountService.prepare(
          new HelperPayoutAccountRequest(accountHolderName, bankAccountNumber, ifscCode));
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
    if (preparedBank != null) {
      payoutAccountService.replacePrepared(helperId, com.helpinminutes.api.users.model.UserRole.HELPER, preparedBank);
    }

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
        estimatedWaitMinutes(position),
        payoutAccountService.getCurrent(p.getUserId()));
  }

  public static HelperBankDetailsResponse toBankDetails(HelperPayoutAccountEntity account) {
    return PayoutAccountService.toResponse(account);
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
