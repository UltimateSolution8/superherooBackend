package com.helpinminutes.api.photos.service;

import com.helpinminutes.api.config.RabbitConfig;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.photos.dto.ConfirmUploadRequest;
import com.helpinminutes.api.photos.dto.PhotoEvent;
import com.helpinminutes.api.photos.dto.PhotoUploadRequest;
import com.helpinminutes.api.photos.dto.PresignedUploadResponse;
import com.helpinminutes.api.photos.model.PhotoEntity;
import com.helpinminutes.api.photos.repository.PhotoRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskSelfieStage;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.service.TaskService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final PhotoRepository photoRepository;
    private final TaskRepository taskRepository;
    private final S3PresignerService s3PresignerService;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final TaskService taskService;

    public PhotoService(PhotoRepository photoRepository,
            TaskRepository taskRepository,
            S3PresignerService s3PresignerService,
            RabbitTemplate rabbitTemplate,
            MeterRegistry meterRegistry,
            TaskService taskService) {
        this.photoRepository = photoRepository;
        this.taskRepository = taskRepository;
        this.s3PresignerService = s3PresignerService;
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        this.taskService = taskService;
    }

    @Transactional
    public PresignedUploadResponse requestUpload(UUID userId, PhotoUploadRequest req) {
        meterRegistry.counter("photo_presigned_requests_total", "photo_type", req.getPhotoType()).increment();

        TaskEntity task = taskRepository.findById(req.getJobId())
                .orElseThrow(() -> new NotFoundException("Task not found"));

        if (!userId.equals(task.getAssignedHelperId())) {
            throw new ForbiddenException("You are not the assigned helper for this task");
        }

        PhotoEntity photo = new PhotoEntity();
        photo.setJobId(task.getId());
        photo.setUserId(userId);
        photo.setPhotoType(req.getPhotoType());

        UUID photoId = UUID.randomUUID();
        photo.setId(photoId);

        String ext = "jpg";
        String filename = req.getPhotoType() + "-selfie-" + Instant.now().toEpochMilli() + "." + ext;
        photo.setFilename(filename);

        String environmentPath = System.getenv("APP_ENV") != null ? System.getenv("APP_ENV") : "dev";
        String storagePath = "photos/" + environmentPath + "/" + task.getId() + "/" + photoId + ".jpg";
        photo.setStoragePath(storagePath);

        photo.setStatus("presigned_issued");
        photoRepository.save(photo);

        Duration expiry = Duration.ofMinutes(10);
        PresignedUploadResponse presignedResponse = s3PresignerService.createPresignedPut(photoId, storagePath, expiry);

        log.info("Issued presigned URL for photoId={} userId={} jobId={}", photoId, userId, task.getId());
        return presignedResponse;
    }

    @Transactional
    public void confirmUpload(UUID userId, ConfirmUploadRequest req) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            PhotoEntity photo = photoRepository.findById(req.getPhotoId())
                    .orElseThrow(() -> new NotFoundException("Photo not found"));

            if (!userId.equals(photo.getUserId())) {
                throw new ForbiddenException("Not authorized to confirm this photo");
            }

            String currentStatus = photo.getStatus();
            if ("uploaded".equals(currentStatus) || "processing".equals(currentStatus)
                    || "processed".equals(currentStatus)) {
                log.info("Photo {} is already confirmed (idempotent return). Current status: {}", photo.getId(),
                        currentStatus);
                meterRegistry.counter("photo_upload_confirm_total", "status", "success").increment();
                sample.stop(meterRegistry.timer("photo_processing_duration_seconds"));
                return;
            }

            if (!"presigned_issued".equals(currentStatus)) {
                throw new BadRequestException("Cannot confirm photo in status " + currentStatus);
            }

            photo.setStatus("uploaded");
            photo.setUploadedAt(Instant.now());
            if (req.getSize() != null) {
                photo.setSizeBytes(req.getSize());
            }

            photoRepository.save(photo);

            TaskSelfieStage stage = "completion".equalsIgnoreCase(photo.getPhotoType())
                    ? TaskSelfieStage.COMPLETION
                    : TaskSelfieStage.ARRIVAL;

            double lat = req.getLat() == null ? 0.0 : req.getLat();
            double lng = req.getLng() == null ? 0.0 : req.getLng();
            String addressText = req.getAddressText();
            Instant capturedAt = photo.getUploadedAt();
            if (req.getCapturedAt() != null && !req.getCapturedAt().isBlank()) {
                try {
                    capturedAt = Instant.parse(req.getCapturedAt().trim());
                } catch (DateTimeParseException ignored) {
                    // fall back to uploadedAt
                }
            }

            taskService.attachTaskSelfieFromStorageKey(
                    userId,
                    photo.getJobId(),
                    stage,
                    photo.getStoragePath(),
                    lat,
                    lng,
                    addressText,
                    capturedAt);

            PhotoEvent event = new PhotoEvent(
                    photo.getId(),
                    photo.getJobId(),
                    photo.getUserId(),
                    photo.getStoragePath(),
                    photo.getContentType() != null ? photo.getContentType() : "image/jpeg",
                    photo.getSizeBytes(),
                    photo.getUploadedAt());

            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_PHOTOS, RabbitConfig.ROUTING_KEY_PHOTO_UPLOADED, event);

            log.info("Confirmed photo upload and published event photoId={} jobId={}", photo.getId(), photo.getJobId());
            meterRegistry.counter("photo_upload_confirm_total", "status", "success").increment();

        } catch (Exception e) {
            meterRegistry.counter("photo_upload_confirm_total", "status", "failure").increment();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("photo_processing_duration_seconds"));
        }
    }
}
