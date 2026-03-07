package com.helpinminutes.api.photos.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.RabbitConfig;
import com.helpinminutes.api.photos.dto.PhotoEvent;
import com.helpinminutes.api.photos.model.PhotoEntity;
import com.helpinminutes.api.photos.repository.PhotoRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class PhotoProcessorWorker {

    private static final Logger log = LoggerFactory.getLogger(PhotoProcessorWorker.class);

    private final PhotoRepository photoRepository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;
    private final String bucket;

    // Fallback constants
    private static final String MINIO_ENDPOINT = "http://localhost:9000";
    private static final String MINIO_BUCKET = "helpinminutes";
    private static final String MINIO_KEY_ID = "minio";
    private static final String MINIO_KEY_SECRET = "minio12345";

    public PhotoProcessorWorker(
            PhotoRepository photoRepository,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            @Value("${SUPABASE_S3_ENDPOINT:}") String endpoint,
            @Value("${SUPABASE_S3_BUCKET:}") String bucket,
            @Value("${SUPABASE_S3_ACCESS_KEY_ID:}") String keyId,
            @Value("${SUPABASE_S3_ACCESS_KEY_SECRET:}") String keySecret,
            @Value("${SUPABASE_S3_REGION:us-east-1}") String region) {

        this.photoRepository = photoRepository;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;

        String s3Endpoint = endpoint == null || endpoint.isBlank() ? MINIO_ENDPOINT : endpoint.trim();
        this.bucket = bucket == null || bucket.isBlank() ? MINIO_BUCKET : bucket.trim();
        String s3KeyId = keyId == null || keyId.isBlank() ? MINIO_KEY_ID : keyId.trim();
        String s3KeySecret = keySecret == null || keySecret.isBlank() ? MINIO_KEY_SECRET : keySecret.trim();
        String s3Region = region == null || region.isBlank() ? "us-east-1" : region.trim();

        this.s3Client = S3Client.builder()
                .region(Region.of(s3Region))
                .endpointOverride(URI.create(s3Endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3KeyId, s3KeySecret)))
                .build();
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_PHOTO_UPLOADED)
    public void processPhoto(PhotoEvent event) {
        log.info("Received photo event to process: photoId={} jobId={}", event.getPhotoId(), event.getJobId());
        Timer.Sample processingSample = Timer.start(meterRegistry);

        try {
            PhotoEntity photo = photoRepository.findById(event.getPhotoId())
                    .orElseThrow(() -> new IllegalArgumentException("Photo missing from DB: " + event.getPhotoId()));

            photo.setStatus("processing");
            photoRepository.save(photo);

            // Download from S3
            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(photo.getStoragePath())
                    .build());

            byte[] originalContent = s3Object.readAllBytes();

            // 1. Generate Thumbnail (200x200 max)
            ByteArrayOutputStream thumbOs = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(originalContent))
                    .size(200, 200)
                    .outputFormat("jpg")
                    .toOutputStream(thumbOs);
            byte[] thumbBytes = thumbOs.toByteArray();
            String thumbKey = "photos/thumbs/" + photo.getId() + "/thumb.jpg";
            uploadToS3(thumbKey, thumbBytes, "image/jpeg");

            // 2. Generate Optimized Web (800px max, we'll use jpg for compat since native
            // webp is tricky here)
            // But we will name it .webp path for routing or future replacement. Just
            // compressing heavily.
            ByteArrayOutputStream webOs = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(originalContent))
                    .size(800, 800)
                    .outputFormat("jpg")
                    .outputQuality(0.7)
                    .toOutputStream(webOs);
            byte[] webBytes = webOs.toByteArray();
            String webKey = "photos/web/" + photo.getId() + ".webp";
            uploadToS3(webKey, webBytes, "image/jpeg"); // Note: passing mime type as jpeg, path as webp

            // Update photo record metadata
            Map<String, String> metaMap = Map.of(
                    "thumbPath", thumbKey,
                    "webPath", webKey,
                    "originalSize", String.valueOf(originalContent.length),
                    "thumbSize", String.valueOf(thumbBytes.length));
            photo.setMetadata(objectMapper.writeValueAsString(metaMap));
            photo.setStatus("processed");
            photo.setProcessedAt(Instant.now());
            photoRepository.save(photo);

            log.info("Successfully processed photoId={}. Generated thumbs.", photo.getId());

        } catch (Exception e) {
            log.error("Failed to process photo event: {}", event.getPhotoId(), e);
            meterRegistry.counter("photo_processing_failures_total").increment();
            // Optional: Reached max retries logic if headers indicate it, manually push to
            // DLQ or let RabbitMQ handle it via reject.
            // Throwing RuntimeException triggers Spring AMQP to retry / dead-letter
            // depending on config.
            throw new RuntimeException("Image processing failed", e);
        } finally {
            processingSample.stop(meterRegistry.timer("photo_processing_duration_seconds"));
        }
    }

    private void uploadToS3(String key, byte[] content, String contentType) {
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(), RequestBody.fromBytes(content));
    }
}
