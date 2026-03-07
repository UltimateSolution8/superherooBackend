package com.helpinminutes.api.photos.service;

import com.helpinminutes.api.photos.dto.PresignedUploadResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class S3PresignerService {

    private final String endpoint;
    private final String bucket;
    private final String keyId;
    private final String keySecret;
    private final String region;

    // Fallback for dev mode
    private static final String MINIO_ENDPOINT = "http://localhost:9000";
    private static final String MINIO_BUCKET = "helpinminutes";
    private static final String MINIO_KEY_ID = "minio";
    private static final String MINIO_KEY_SECRET = "minio12345";

    public S3PresignerService(
            @Value("${SUPABASE_S3_ENDPOINT:}") String endpoint,
            @Value("${SUPABASE_S3_BUCKET:}") String bucket,
            @Value("${SUPABASE_S3_ACCESS_KEY_ID:}") String keyId,
            @Value("${SUPABASE_S3_ACCESS_KEY_SECRET:}") String keySecret,
            @Value("${SUPABASE_S3_REGION:us-east-1}") String region) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? MINIO_ENDPOINT : endpoint.trim();
        this.bucket = bucket == null || bucket.isBlank() ? MINIO_BUCKET : bucket.trim();
        this.keyId = keyId == null || keyId.isBlank() ? MINIO_KEY_ID : keyId.trim();
        this.keySecret = keySecret == null || keySecret.isBlank() ? MINIO_KEY_SECRET : keySecret.trim();
        this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
    }

    public PresignedUploadResponse createPresignedPut(UUID photoId, String key, Duration expires) {
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(keyId, keySecret)))
                .build();

        PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("image/jpeg")
                .acl(ObjectCannedACL.PUBLIC_READ) // Or omit if using presigned URLs to read too
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expires)
                .putObjectRequest(putReq)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);

        return new PresignedUploadResponse(
                photoId,
                presigned.url().toString(),
                Map.of("Content-Type", "image/jpeg"),
                expires.getSeconds(),
                key);
    }
}
