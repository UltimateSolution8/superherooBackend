package com.helpinminutes.api.kyc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.kyc.dto.KycActionRequest;
import com.helpinminutes.api.kyc.dto.KycStartRequest;
import com.helpinminutes.api.kyc.dto.KycStartResponse;
import com.helpinminutes.api.kyc.dto.KycStartResponse.UploadUrlInfo;
import com.helpinminutes.api.kyc.dto.KycStartResponse.UploadUrls;
import com.helpinminutes.api.kyc.dto.KycStatusResponse;
import com.helpinminutes.api.kyc.dto.AdminKycResponse;
import com.helpinminutes.api.kyc.dto.KycUploadedRequest;
import com.helpinminutes.api.kyc.dto.LiveKycSessionResponse;
import com.helpinminutes.api.kyc.dto.LiveKycSnapshotRequest;
import com.helpinminutes.api.kyc.dto.LiveKycSnapshotUrlResponse;
import com.helpinminutes.api.kyc.model.KycAuditLogEntity;
import com.helpinminutes.api.kyc.model.KycRequestEntity;
import com.helpinminutes.api.kyc.model.KycRequestStatus;
import com.helpinminutes.api.kyc.repository.KycAuditLogRepository;
import com.helpinminutes.api.kyc.repository.KycRequestRepository;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.storage.SupabaseStorageService.ObjectMeta;
import com.helpinminutes.api.config.ZegoProperties;
import com.helpinminutes.api.kyc.zego.ZegoCloudRecordingService;
import com.helpinminutes.api.kyc.zego.ZegoTokenService;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.MovieHeaderBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;

@Service
public class KycService {
    private static final Logger log = LoggerFactory.getLogger(KycService.class);
    private static final long MAX_VIDEO_BYTES = 25L * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final double MAX_VIDEO_SECONDS = 20.0;

    private final KycRequestRepository kycRequestRepository;
    private final KycAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final HelperProfileRepository helperProfiles;
    private final NotificationQueueService notificationQueue;
    private final SupabaseStorageService storageService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final ZegoTokenService zegoTokenService;
    private final ZegoCloudRecordingService recordingService;
    private final ZegoProperties zegoProps;

    private static final String EXCHANGE = "him.kyc";
    private static final String ROUTING_KEY = "kyc.processing";

    public KycService(KycRequestRepository kycRequestRepository,
            KycAuditLogRepository auditLogRepository,
            UserRepository userRepository,
            HelperProfileRepository helperProfiles,
            NotificationQueueService notificationQueue,
            SupabaseStorageService storageService,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            ZegoTokenService zegoTokenService,
            ZegoCloudRecordingService recordingService,
            ZegoProperties zegoProps) {
        this.kycRequestRepository = kycRequestRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.helperProfiles = helperProfiles;
        this.notificationQueue = notificationQueue;
        this.storageService = storageService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.zegoTokenService = zegoTokenService;
        this.recordingService = recordingService;
        this.zegoProps = zegoProps;
    }

    @Transactional
    public KycStartResponse startKyc(UUID helperId, KycStartRequest req) {
        UserEntity user = userRepository.findById(helperId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        KycRequestEntity entity = new KycRequestEntity();
        entity.setUser(user);
        entity.setStatus(KycRequestStatus.SUBMITTED);
        entity.setRetentionExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS));

        kycRequestRepository.save(entity);

        // Generate presigned URLs
        var videoUpload = storageService.generateKycPresignedPut("video", helperId, ".mp4");
        var docFrontUpload = storageService.generateKycPresignedPut("doc-front", helperId, ".jpg");
        var docBackUpload = storageService.generateKycPresignedPut("doc-back", helperId, ".jpg");

        UploadUrlInfo videoUrl = new UploadUrlInfo(
                videoUpload.url(),
                "PUT",
                videoUpload.expiresInSeconds(),
                videoUpload.key());
        UploadUrlInfo docFrontUrl = new UploadUrlInfo(
                docFrontUpload.url(),
                "PUT",
                docFrontUpload.expiresInSeconds(),
                docFrontUpload.key());
        UploadUrlInfo docBackUrl = new UploadUrlInfo(
                docBackUpload.url(),
                "PUT",
                docBackUpload.expiresInSeconds(),
                docBackUpload.key());

        UploadUrls urls = new UploadUrls(videoUrl, docFrontUrl, docBackUrl);

        // Audit log
        auditLog("SUBMITTED", entity, user, "KYC started");

        return new KycStartResponse(entity.getId(), urls, entity.getStatus().name());
    }

    @Transactional
    public void markUploaded(UUID kycId, UUID helperId, KycUploadedRequest req) {
        KycRequestEntity entity = kycRequestRepository.findById(kycId)
                .orElseThrow(() -> new NotFoundException("KYC request not found"));

        if (!entity.getUser().getId().equals(helperId)) {
            throw new BadRequestException("Unauthorized access to KYC request");
        }

        validateKycKey(helperId, req.s3Keys().video(), "video");
        validateKycKey(helperId, req.s3Keys().docFront(), "doc-front");
        if (req.s3Keys().docBack() != null && !req.s3Keys().docBack().isBlank()) {
            validateKycKey(helperId, req.s3Keys().docBack(), "doc-back");
        }

        validateObjectMeta(req.s3Keys().video(), true);
        validateObjectMeta(req.s3Keys().docFront(), false);
        if (req.s3Keys().docBack() != null && !req.s3Keys().docBack().isBlank()) {
            validateObjectMeta(req.s3Keys().docBack(), false);
        }

        double duration = extractVideoDurationSeconds(req.s3Keys().video());
        if (duration > MAX_VIDEO_SECONDS) {
            throw new BadRequestException("Video duration exceeds 20 seconds limit");
        }
        if (req.durationSeconds() != null && Math.abs(req.durationSeconds() - duration) > 3) {
            log.warn("Client duration {} differs from server duration {}", req.durationSeconds(), duration);
        }

        entity.setVideoPath(req.s3Keys().video());
        entity.setDocFrontPath(req.s3Keys().docFront());
        entity.setDocBackPath(req.s3Keys().docBack());
        entity.setStatus(KycRequestStatus.PENDING_PROCESSING);

        // Publish to RabbitMQ
        try {
            Map<String, Object> job = Map.of(
                    "kycId", kycId.toString(),
                    "helperId", helperId.toString(),
                    "videoKey", req.s3Keys().video(),
                    "docFrontKey", req.s3Keys().docFront(),
                    "docBackKey", req.s3Keys().docBack() != null ? req.s3Keys().docBack() : "");
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, job);
            log.info("Published KYC processing job for {}", kycId);
        } catch (Exception e) {
            log.error("Failed to publish KYC job", e);
            throw new RuntimeException("Failed to enqueue processing job");
        }

        auditLog("UPLOADED", entity, entity.getUser(), "Media uploaded");
    }

    public KycStatusResponse getStatus(UUID kycId, UUID helperId) {
        KycRequestEntity entity = kycRequestRepository.findById(kycId)
                .orElseThrow(() -> new NotFoundException("KYC request not found"));

        if (!entity.getUser().getId().equals(helperId)) {
            throw new BadRequestException("Unauthorized access to KYC request");
        }

        return new KycStatusResponse(
                entity.getId(),
                entity.getStatus().name(),
                entity.getCreatedAt(),
                entity.getRecommendedAction(),
                entity.getFaceMatchScore(),
                entity.getLivenessScore(),
                entity.getReviewerNotes());
    }

    @Transactional
    public LiveKycSessionResponse startLiveKyc(UUID helperId, UUID adminId) {
        UserEntity helper = userRepository.findById(helperId)
                .orElseThrow(() -> new NotFoundException("Helper not found"));
        UserEntity admin = userRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("Admin not found"));

        KycRequestEntity entity = kycRequestRepository.findActiveLiveSessions(helperId,
                java.util.List.of(KycRequestStatus.SUBMITTED, KycRequestStatus.REVIEW))
                .stream()
                .findFirst()
                .orElse(null);
        if (entity == null) {
            entity = new KycRequestEntity();
            entity.setUser(helper);
            entity.setStatus(KycRequestStatus.SUBMITTED);
            entity.setRetentionExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS));
        }

        String roomId = entity.getLiveRoomId();
        if (roomId == null || roomId.isBlank()) {
            roomId = "kyc-" + helperId + "-" + System.currentTimeMillis();
            entity.setLiveRoomId(roomId);
        }
        entity.setLiveStartedAt(Instant.now());
        entity.setLiveEndedAt(null);

        // Recording is optional; skip to avoid blocking live KYC start.

        kycRequestRepository.save(entity);
        auditLog("LIVE_START", entity, admin, "Live KYC started");

        long ttl = Math.max(60, zegoProps.tokenTtlSeconds());
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        String token = zegoTokenService.generateKitToken(adminId.toString(), roomId, resolveHelperName(admin), ttl);

        return new LiveKycSessionResponse(
                entity.getId(),
                helperId,
                resolveHelperName(helper),
                roomId,
                token,
                entity.getStatus().name(),
                expiresAt);
    }

    public LiveKycSessionResponse getLiveSessionForHelper(UUID helperId) {
        UserEntity helper = userRepository.findById(helperId)
                .orElseThrow(() -> new NotFoundException("Helper not found"));
        var sessions = kycRequestRepository.findActiveLiveSessions(helperId,
                java.util.List.of(KycRequestStatus.SUBMITTED, KycRequestStatus.REVIEW));
        if (sessions.isEmpty()) {
            return null;
        }
        KycRequestEntity entity = sessions.get(0);
        if (entity.getLiveRoomId() == null || entity.getLiveRoomId().isBlank()) {
            return null;
        }
        long ttl = Math.max(60, zegoProps.tokenTtlSeconds());
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        String token = zegoTokenService.generateKitToken(helperId.toString(), entity.getLiveRoomId(), resolveHelperName(helper), ttl);
        return new LiveKycSessionResponse(
                entity.getId(),
                helperId,
                resolveHelperName(helper),
                entity.getLiveRoomId(),
                token,
                entity.getStatus().name(),
                expiresAt);
    }

    public LiveKycSnapshotUrlResponse createLiveSnapshotUrl(UUID kycId, UUID adminId, String kind) {
        KycRequestEntity entity = kycRequestRepository.findById(kycId)
                .orElseThrow(() -> new NotFoundException("KYC request not found"));
        userRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("Admin not found"));
        String normalized = normalizeSnapshotKind(kind);
        var upload = storageService.generateKycPresignedPut(normalized, entity.getUser().getId(), ".jpg");
        return new LiveKycSnapshotUrlResponse(upload.url(), upload.key(), upload.expiresInSeconds());
    }

    @Transactional
    public void confirmLiveSnapshot(UUID kycId, UUID adminId, LiveKycSnapshotRequest req) {
        KycRequestEntity entity = kycRequestRepository.findById(kycId)
                .orElseThrow(() -> new NotFoundException("KYC request not found"));
        UserEntity admin = userRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("Admin not found"));

        String normalized = normalizeSnapshotKind(req.kind());
        validateKycKey(entity.getUser().getId(), req.key(), normalized);
        validateObjectMeta(req.key(), false);

        if ("selfie".equals(normalized)) {
            entity.setSelfiePath(req.key());
        } else if ("doc-front".equals(normalized)) {
            entity.setDocFrontPath(req.key());
        } else if ("doc-back".equals(normalized)) {
            entity.setDocBackPath(req.key());
        }

        helperProfiles.findById(entity.getUser().getId()).ifPresent(profile -> {
            if ("selfie".equals(normalized)) {
                profile.setKycSelfieUrl(storageService.buildPublicUrl(req.key()));
            } else if ("doc-front".equals(normalized)) {
                profile.setKycDocFrontUrl(storageService.buildPublicUrl(req.key()));
            } else if ("doc-back".equals(normalized)) {
                profile.setKycDocBackUrl(storageService.buildPublicUrl(req.key()));
            }
            if (profile.getKycSubmittedAt() == null) {
                profile.setKycSubmittedAt(Instant.now());
            }
            if (profile.getKycStatus() == null) {
                profile.setKycStatus(HelperKycStatus.PENDING);
            }
            helperProfiles.save(profile);
        });

        auditLog("LIVE_SNAPSHOT_" + normalized.toUpperCase(), entity, admin, "Snapshot stored");
    }

    @Transactional
    public void endLiveSession(UUID kycId, UUID adminId) {
        KycRequestEntity entity = kycRequestRepository.findById(kycId)
                .orElseThrow(() -> new NotFoundException("KYC request not found"));
        UserEntity admin = userRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("Admin not found"));
        if (entity.getLiveRecordTaskId() != null && !entity.getLiveRecordTaskId().isBlank()) {
            recordingService.stopRecording(entity.getLiveRecordTaskId());
        }
        entity.setLiveEndedAt(Instant.now());
        auditLog("LIVE_END", entity, admin, "Live KYC ended");
    }

    @Transactional
    public void attachLiveRecording(String roomId, String recordTaskId, String recordingUrl) {
        KycRequestEntity entity = null;
        if (recordTaskId != null && !recordTaskId.isBlank()) {
            entity = kycRequestRepository.findTop1ByLiveRecordTaskId(recordTaskId).orElse(null);
        }
        if (entity == null && roomId != null && !roomId.isBlank()) {
            entity = kycRequestRepository.findTop1ByLiveRoomId(roomId).orElse(null);
        }
        if (entity == null) {
            log.warn("Live recording callback did not match any KYC request (roomId={}, taskId={})", roomId, recordTaskId);
            return;
        }
        if (recordTaskId != null && !recordTaskId.isBlank()) {
            entity.setLiveRecordTaskId(recordTaskId);
        }
        entity.setLiveRecordingUrl(recordingUrl);
    }

    // --- Admin API ---

    public Page<KycStatusResponse> listRequests(KycRequestStatus status, Pageable pageable) {
        Page<KycRequestEntity> page;
        if (status != null) {
            page = kycRequestRepository.findByStatus(status, pageable);
        } else {
            page = kycRequestRepository.findAll(pageable);
        }
        return page.map(e -> new KycStatusResponse(
                e.getId(),
                e.getStatus().name(),
                e.getCreatedAt(),
                e.getRecommendedAction(),
                e.getFaceMatchScore(),
                e.getLivenessScore(),
                e.getReviewerNotes()));
    }

    public Page<AdminKycResponse> listRequestsForAdmin(KycRequestStatus status, Pageable pageable) {
        Page<KycRequestEntity> page;
        if (status != null) {
            page = kycRequestRepository.findByStatus(status, pageable);
        } else {
            page = kycRequestRepository.findAll(pageable);
        }
        return page.map(e -> new AdminKycResponse(
                e.getId(),
                e.getUser() != null ? e.getUser().getId() : null,
                e.getUser() != null ? e.getUser().getDisplayName() : null,
                e.getStatus().name(),
                e.getCreatedAt(),
                resolveRecordingUrl(e.getVideoPath(), e.getLiveRecordingUrl()),
                storageService.generatePresignedGetUrl(e.getDocFrontPath(), java.time.Duration.ofMinutes(15)),
                storageService.generatePresignedGetUrl(e.getDocBackPath(), java.time.Duration.ofMinutes(15)),
                storageService.generatePresignedGetUrl(e.getSelfiePath(), java.time.Duration.ofMinutes(15)),
                e.getLiveRoomId(),
                e.getLiveRecordingUrl(),
                e.getLiveStartedAt(),
                e.getLiveEndedAt(),
                e.getRecommendedAction(),
                e.getFaceMatchScore(),
                e.getLivenessScore(),
                e.getReviewerNotes()));
    }

    @Transactional
    public void adminAction(UUID kycId, UUID adminId, KycActionRequest req) {
        KycRequestEntity entity = kycRequestRepository.findById(kycId)
                .orElseThrow(() -> new NotFoundException("KYC request not found"));

        UserEntity admin = userRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("Admin not found"));

        String action = req.action().toUpperCase();
        if ("APPROVE".equals(action)) {
            entity.setStatus(KycRequestStatus.APPROVED);
            helperProfiles.findById(entity.getUser().getId()).ifPresent(profile -> {
                profile.setKycStatus(HelperKycStatus.APPROVED);
                profile.setKycRejectionReason(null);
                if (entity.getDocFrontPath() != null) {
                    profile.setKycDocFrontUrl(storageService.buildPublicUrl(entity.getDocFrontPath()));
                }
                if (entity.getDocBackPath() != null) {
                    profile.setKycDocBackUrl(storageService.buildPublicUrl(entity.getDocBackPath()));
                }
                if (entity.getSelfiePath() != null) {
                    profile.setKycSelfieUrl(storageService.buildPublicUrl(entity.getSelfiePath()));
                }
                if (profile.getKycSubmittedAt() == null) {
                    profile.setKycSubmittedAt(Instant.now());
                }
                helperProfiles.save(profile);
            });
            notificationQueue.enqueueKycApproved(entity.getUser().getId());
        } else if ("REJECT".equals(action)) {
            entity.setStatus(KycRequestStatus.REJECTED);
            helperProfiles.findById(entity.getUser().getId()).ifPresent(profile -> {
                profile.setKycStatus(HelperKycStatus.REJECTED);
                profile.setKycRejectionReason(req.remarks());
                helperProfiles.save(profile);
            });
        } else {
            throw new BadRequestException("Invalid action: " + action);
        }

        entity.setReviewerAdmin(admin);
        entity.setReviewerNotes(req.remarks());
        entity.setProcessedAt(Instant.now());

        auditLog(action, entity, admin, req.remarks());
    }

    private void auditLog(String action, KycRequestEntity kyc, UserEntity actor, String notes) {
        KycAuditLogEntity logItem = new KycAuditLogEntity();
        logItem.setKycRequest(kyc);
        logItem.setActor(actor);
        logItem.setAction(action);
        logItem.setPayload("{\"notes\":\"" + notes + "\"}");
        auditLogRepository.save(logItem);
    }

    private String resolveHelperName(UserEntity user) {
        if (user == null) return null;
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getPhone();
    }

    private String normalizeSnapshotKind(String kind) {
        if (kind == null) {
            throw new BadRequestException("Missing snapshot kind");
        }
        String normalized = kind.trim().toLowerCase();
        if ("selfie".equals(normalized) || "doc-front".equals(normalized) || "doc-back".equals(normalized)) {
            return normalized;
        }
        throw new BadRequestException("Invalid snapshot kind");
    }

    private String resolveRecordingUrl(String videoKey, String liveRecordingUrl) {
        if (liveRecordingUrl != null && !liveRecordingUrl.isBlank()) {
            return liveRecordingUrl;
        }
        return storageService.generatePresignedGetUrl(videoKey, java.time.Duration.ofMinutes(15));
    }

    private void validateKycKey(UUID helperId, String key, String kind) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("Missing KYC " + kind + " key");
        }
        String helperMarker = "/" + helperId + "/";
        if (!key.startsWith("helper-kyc/") || !key.contains(helperMarker)) {
            throw new BadRequestException("Invalid KYC " + kind + " key");
        }
        String kindMarker = "/" + kind + "-";
        if (!key.contains(kindMarker)) {
            throw new BadRequestException("Invalid KYC " + kind + " key");
        }
    }

    private void validateObjectMeta(String key, boolean isVideo) {
        ObjectMeta meta = storageService.headObject(key);
        String ct = meta.contentType() == null ? "" : meta.contentType().toLowerCase();
        if (isVideo) {
            if (!ct.startsWith("video/")) {
                throw new BadRequestException("Invalid video content type");
            }
            if (meta.contentLength() > MAX_VIDEO_BYTES) {
                throw new BadRequestException("Video file too large");
            }
        } else {
            if (!ct.startsWith("image/")) {
                throw new BadRequestException("Invalid image content type");
            }
            if (meta.contentLength() > MAX_IMAGE_BYTES) {
                throw new BadRequestException("Image file too large");
            }
        }
    }

    private double extractVideoDurationSeconds(String key) {
        Path tmp = null;
        try {
            tmp = storageService.downloadToTempFile(key);
            try (IsoFile isoFile = new IsoFile(tmp.toString())) {
                MovieHeaderBox mvhd = isoFile.getMovieBox().getMovieHeaderBox();
                if (mvhd == null || mvhd.getTimescale() == 0) {
                    return 0;
                }
                return (double) mvhd.getDuration() / (double) mvhd.getTimescale();
            }
        } catch (Exception e) {
            throw new BadRequestException("Could not read video metadata");
        } finally {
            if (tmp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tmp);
                } catch (Exception ignored) {}
            }
        }
    }
}
