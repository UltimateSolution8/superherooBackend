package com.helpinminutes.api.storage;

import com.helpinminutes.api.errors.BadRequestException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.core.ResponseInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class SupabaseStorageService {
  private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);

  private final String endpoint;
  private final String bucket;
  private final String keyId;
  private final String keySecret;
  private final String region;
  private final String publicBaseUrl;
  private final String appEnv;

  // MinIO fallback config (matches Docker Compose defaults)
  private static final String MINIO_ENDPOINT = "http://localhost:9000";
  private static final String MINIO_BUCKET = "helpinminutes";
  private static final String MINIO_KEY_ID = "minio";
  private static final String MINIO_KEY_SECRET = "minio12345";

  public SupabaseStorageService(
      @Value("${SUPABASE_S3_ENDPOINT:}") String endpoint,
      @Value("${SUPABASE_S3_BUCKET:}") String bucket,
      @Value("${SUPABASE_S3_ACCESS_KEY_ID:}") String keyId,
      @Value("${SUPABASE_S3_ACCESS_KEY_SECRET:}") String keySecret,
      @Value("${SUPABASE_S3_REGION:us-east-1}") String region,
      @Value("${SUPABASE_PUBLIC_BASE_URL:}") String publicBaseUrl,
      @Value("${app.env:dev}") String appEnv) {
    this.endpoint = endpoint == null ? "" : endpoint.trim();
    this.bucket = bucket == null ? "" : bucket.trim();
    this.keyId = keyId == null ? "" : keyId.trim();
    this.keySecret = keySecret == null ? "" : keySecret.trim();
    this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
    this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    this.appEnv = appEnv == null ? "dev" : appEnv.trim();
  }

  public boolean isConfigured() {
    return !endpoint.isBlank() && !bucket.isBlank() && !keyId.isBlank() && !keySecret.isBlank();
  }

  private boolean isDevMode() {
    return "dev".equalsIgnoreCase(appEnv);
  }

  public String uploadHelperKycDoc(UUID helperId, String kind, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BadRequestException(kind + " file is required");
    }

    String contentType = safeContentType(file.getContentType());
    String ext = detectExtension(contentType, file.getOriginalFilename());
    String key = buildKey("helper-kyc", helperId.toString(), kind, ext);

    if (isConfigured()) {
      return uploadFile(file, contentType, key, "document");
    }
    if (isDevMode()) {
      return uploadToMinioFallback(file, contentType, key, "document");
    }
    throw new BadRequestException("Storage is not configured");
  }

  public String uploadTaskSelfie(UUID taskId, UUID helperId, String stage, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BadRequestException("selfie file is required");
    }
    String contentType = safeContentType(file.getContentType());
    String ext = detectExtension(contentType, file.getOriginalFilename());
    String key = buildKey("task-selfies", taskId + "/" + helperId, stage, ext);

    if (isConfigured()) {
      return uploadFile(file, contentType, key, "selfie");
    }
    if (isDevMode()) {
      return uploadToMinioFallback(file, contentType, key, "selfie");
    }
    throw new BadRequestException("Storage is not configured");
  }

  public String uploadLearningAsset(UUID adminUserId, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BadRequestException("Learning file is required");
    }
    if (file.getSize() > 200L * 1024L * 1024L) {
      throw new BadRequestException("Learning file is too large (max 200 MB)");
    }

    String contentType = safeContentType(file.getContentType());
    if (!isAllowedLearningContent(contentType, file.getOriginalFilename())) {
      throw new BadRequestException("Only video, audio, PDF, and links are supported");
    }
    String ext = detectExtension(contentType, file.getOriginalFilename());
    String kind = classifyLearningKind(contentType, file.getOriginalFilename());
    String key = buildKey("learning", adminUserId.toString(), kind, ext);

    if (isConfigured()) {
      return uploadFile(file, contentType, key, "learning content");
    }
    if (isDevMode()) {
      return uploadToMinioFallback(file, contentType, key, "learning content");
    }
    throw new BadRequestException("Storage is not configured");
  }

  public String generateKycPresignedPutUrl(String kind, UUID helperId, String ext) {
    return generateKycPresignedPut(kind, helperId, ext).url();
  }

  public PresignedUpload generateKycPresignedPut(String kind, UUID helperId, String ext) {
    String contentType = kind.contains("video") ? "video/mp4" : "image/jpeg";
    String key = buildKey("helper-kyc", helperId.toString(), kind, ext);
    String url;
    if (isConfigured()) {
      url = presignPutUrl(endpoint, bucket, keyId, keySecret, region, key, contentType);
    } else if (isDevMode()) {
      url = presignPutUrl(MINIO_ENDPOINT, MINIO_BUCKET, MINIO_KEY_ID, MINIO_KEY_SECRET, "us-east-1", key, contentType);
    } else {
      throw new BadRequestException("Storage is not configured");
    }
    return new PresignedUpload(url, key, 900);
  }

  public String generatePresignedGetUrl(String key, Duration ttl) {
    if (key == null || key.isBlank()) {
      return null;
    }
    if (isConfigured()) {
      return presignGetUrl(endpoint, bucket, keyId, keySecret, region, key, ttl);
    }
    if (isDevMode()) {
      return presignGetUrl(MINIO_ENDPOINT, MINIO_BUCKET, MINIO_KEY_ID, MINIO_KEY_SECRET, "us-east-1", key, ttl);
    }
    throw new BadRequestException("Storage is not configured");
  }

  public ObjectMeta headObject(String key) {
    if (key == null || key.isBlank()) {
      throw new BadRequestException("Missing object key");
    }
    if (isConfigured()) {
      return headObject(endpoint, bucket, keyId, keySecret, region, key);
    }
    if (isDevMode()) {
      return headObject(MINIO_ENDPOINT, MINIO_BUCKET, MINIO_KEY_ID, MINIO_KEY_SECRET, "us-east-1", key);
    }
    throw new BadRequestException("Storage is not configured");
  }

  public Path downloadToTempFile(String key) {
    if (key == null || key.isBlank()) {
      throw new BadRequestException("Missing object key");
    }
    if (isConfigured()) {
      return downloadToTempFile(endpoint, bucket, keyId, keySecret, region, key);
    }
    if (isDevMode()) {
      return downloadToTempFile(MINIO_ENDPOINT, MINIO_BUCKET, MINIO_KEY_ID, MINIO_KEY_SECRET, "us-east-1", key);
    }
    throw new BadRequestException("Storage is not configured");
  }

  private String uploadFile(MultipartFile file, String contentType, String key, String fileLabel) {
    try (S3Client s3 = s3Client()) {
      PutObjectRequest req = PutObjectRequest.builder()
          .bucket(bucket)
          .key(key)
          .contentType(contentType)
          .build();

      s3.putObject(req, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
      return toPublicUrl(key);
    } catch (IOException e) {
      log.warn("Upload failed: could not read {} file (key={}): {}", fileLabel, key, e.getMessage());
      throw new BadRequestException("Could not read uploaded file");
    } catch (Exception e) {
      log.warn("Upload failed for {} (key={}): {}", fileLabel, key, e.getMessage());
      throw new BadRequestException("Could not upload " + fileLabel + " to storage");
    }
  }

  private String uploadToMinioFallback(MultipartFile file, String contentType, String key, String fileLabel) {
    try {
      AwsBasicCredentials creds = AwsBasicCredentials.create(MINIO_KEY_ID, MINIO_KEY_SECRET);
      try (S3Client s3 = S3Client.builder()
          .endpointOverride(URI.create(MINIO_ENDPOINT))
          .forcePathStyle(true)
          .region(Region.US_EAST_1)
          .credentialsProvider(StaticCredentialsProvider.create(creds))
          .httpClient(defaultHttpClient())
          .overrideConfiguration(defaultOverrideConfig())
          .build()) {

        // Auto-create bucket if it doesn't exist
        try {
          s3.headBucket(HeadBucketRequest.builder().bucket(MINIO_BUCKET).build());
        } catch (Exception e) {
          s3.createBucket(CreateBucketRequest.builder().bucket(MINIO_BUCKET).build());
          log.info("Created MinIO bucket: {}", MINIO_BUCKET);
        }

        PutObjectRequest req = PutObjectRequest.builder()
            .bucket(MINIO_BUCKET)
            .key(key)
            .contentType(contentType)
            .build();
        s3.putObject(req, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        return MINIO_ENDPOINT + "/" + MINIO_BUCKET + "/" + key;
      }
    } catch (Exception e) {
      log.warn("MinIO fallback upload failed for {}: {}. Returning dev placeholder URL.", fileLabel, e.getMessage());
      return "dev://placeholder/" + MINIO_BUCKET + "/" + key;
    }
  }

  private S3Client s3Client() {
    return s3ClientFor(endpoint, bucket, keyId, keySecret, region);
  }

  private S3Client s3ClientFor(
      String targetEndpoint,
      String targetBucket,
      String accessKey,
      String secretKey,
      String targetRegion) {
    AwsBasicCredentials creds = AwsBasicCredentials.create(accessKey, secretKey);
    return S3Client.builder()
        .endpointOverride(URI.create(targetEndpoint))
        .forcePathStyle(true)
        .region(Region.of(targetRegion))
        .credentialsProvider(StaticCredentialsProvider.create(creds))
        .httpClient(defaultHttpClient())
        .overrideConfiguration(defaultOverrideConfig())
        .build();
  }

  private S3Presigner s3PresignerFor(
      String targetEndpoint,
      String accessKey,
      String secretKey,
      String targetRegion) {
    AwsBasicCredentials creds = AwsBasicCredentials.create(accessKey, secretKey);
    S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
    return S3Presigner.builder()
        .endpointOverride(URI.create(targetEndpoint))
        .region(Region.of(targetRegion))
        .credentialsProvider(StaticCredentialsProvider.create(creds))
        .serviceConfiguration(s3Config)
        .build();
  }

  private String presignPutUrl(
      String targetEndpoint,
      String targetBucket,
      String accessKey,
      String secretKey,
      String targetRegion,
      String key,
      String contentType) {
    try (S3Presigner presigner = s3PresignerFor(targetEndpoint, accessKey, secretKey, targetRegion)) {

      PutObjectRequest putReq = PutObjectRequest.builder()
          .bucket(targetBucket)
          .key(key)
          .contentType(contentType)
          .build();
      PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
          .signatureDuration(Duration.ofMinutes(15))
          .putObjectRequest(putReq)
          .build();
      PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
      return presigned.url().toString();
    }
  }

  private String presignGetUrl(
      String targetEndpoint,
      String targetBucket,
      String accessKey,
      String secretKey,
      String targetRegion,
      String key,
      Duration ttl) {
    try (S3Presigner presigner = s3PresignerFor(targetEndpoint, accessKey, secretKey, targetRegion)) {
      GetObjectRequest getReq = GetObjectRequest.builder()
          .bucket(targetBucket)
          .key(key)
          .build();
      GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
          .signatureDuration(ttl == null ? Duration.ofMinutes(15) : ttl)
          .getObjectRequest(getReq)
          .build();
      PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
      return presigned.url().toString();
    }
  }

  private ObjectMeta headObject(
      String targetEndpoint,
      String targetBucket,
      String accessKey,
      String secretKey,
      String targetRegion,
      String key) {
    try (S3Client s3 = s3ClientFor(targetEndpoint, targetBucket, accessKey, secretKey, targetRegion)) {
      HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
          .bucket(targetBucket)
          .key(key)
          .build());
      return new ObjectMeta(head.contentType(), head.contentLength());
    } catch (Exception e) {
      throw new BadRequestException("Could not read uploaded file metadata");
    }
  }

  private Path downloadToTempFile(
      String targetEndpoint,
      String targetBucket,
      String accessKey,
      String secretKey,
      String targetRegion,
      String key) {
    try (S3Client s3 = s3ClientFor(targetEndpoint, targetBucket, accessKey, secretKey, targetRegion)) {
      ResponseInputStream<GetObjectResponse> stream = s3.getObject(GetObjectRequest.builder()
          .bucket(targetBucket)
          .key(key)
          .build());
      Path tmp = Files.createTempFile("kyc-", ".bin");
      Files.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING);
      return tmp;
    } catch (Exception e) {
      throw new BadRequestException("Could not download uploaded file");
    }
  }

  public record ObjectMeta(String contentType, long contentLength) {}
  public record PresignedUpload(String url, String key, int expiresInSeconds) {}

  private static SdkHttpClient defaultHttpClient() {
    return ApacheHttpClient.builder()
        .connectionTimeout(Duration.ofSeconds(2))
        .socketTimeout(Duration.ofSeconds(5))
        .build();
  }

  private static ClientOverrideConfiguration defaultOverrideConfig() {
    return ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(5))
        .apiCallAttemptTimeout(Duration.ofSeconds(2))
        .build();
  }

  public String buildPublicUrl(String key) {
    return toPublicUrl(key);
  }

  private String toPublicUrl(String key) {
    String explicit = trimSlash(publicBaseUrl);
    if (!explicit.isBlank()) {
      return explicit + "/" + bucket + "/" + key;
    }

    // Supabase S3 endpoint:
    // https://<ref>.storage.supabase.co/storage/v1/s3
    // Public object URL:
    // https://<ref>.supabase.co/storage/v1/object/public/<bucket>/<key>
    String base = trimSlash(endpoint);
    String marker = ".storage.supabase.co/storage/v1/s3";
    int idx = base.indexOf(marker);
    if (idx > "https://".length()) {
      String prefix = base.substring(0, idx);
      String ref = prefix.replace("https://", "").replace("http://", "");
      if (!ref.isBlank()) {
        return "https://" + ref + ".supabase.co/storage/v1/object/public/" + bucket + "/" + key;
      }
    }

    // Non-supabase fallback
    return base + "/" + bucket + "/" + key;
  }

  private static String trimSlash(String s) {
    if (s == null || s.isBlank())
      return "";
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String safeContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return "application/octet-stream";
    }
    return contentType;
  }

  private static String detectExtension(String contentType, String originalName) {
    String lowerCt = contentType.toLowerCase(Locale.ROOT);
    if (lowerCt.contains("jpeg"))
      return ".jpg";
    if (lowerCt.contains("png"))
      return ".png";
    if (lowerCt.contains("pdf"))
      return ".pdf";
    if (lowerCt.contains("mp4"))
      return ".mp4";
    if (lowerCt.contains("quicktime"))
      return ".mov";
    if (lowerCt.contains("mpeg"))
      return ".mp3";
    if (lowerCt.contains("wav"))
      return ".wav";
    if (lowerCt.contains("webm"))
      return ".webm";
    if (lowerCt.contains("ogg"))
      return ".ogg";

    if (originalName != null) {
      String n = originalName.toLowerCase(Locale.ROOT);
      if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
        return ".jpg";
      if (n.endsWith(".png"))
        return ".png";
      if (n.endsWith(".pdf"))
        return ".pdf";
      if (n.endsWith(".mp4"))
        return ".mp4";
      if (n.endsWith(".mov"))
        return ".mov";
      if (n.endsWith(".mp3"))
        return ".mp3";
      if (n.endsWith(".wav"))
        return ".wav";
      if (n.endsWith(".webm"))
        return ".webm";
      if (n.endsWith(".ogg"))
        return ".ogg";
    }
    return ".bin";
  }

  private static boolean isAllowedLearningContent(String contentType, String originalName) {
    String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("pdf")) {
      return true;
    }
    if (originalName == null || originalName.isBlank()) return false;
    String n = originalName.toLowerCase(Locale.ROOT);
    return n.endsWith(".pdf")
        || n.endsWith(".mp4")
        || n.endsWith(".mov")
        || n.endsWith(".mp3")
        || n.endsWith(".wav")
        || n.endsWith(".webm")
        || n.endsWith(".ogg");
  }

  private static String classifyLearningKind(String contentType, String originalName) {
    String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    if (ct.startsWith("video/")) return "video";
    if (ct.startsWith("audio/")) return "audio";
    if (ct.contains("pdf")) return "pdf";
    if (originalName != null) {
      String n = originalName.toLowerCase(Locale.ROOT);
      if (n.endsWith(".pdf")) return "pdf";
      if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg")) return "audio";
      if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".webm")) return "video";
    }
    return "resource";
  }

  private static String buildKey(String base, String idPath, String kind, String ext) {
    java.time.LocalDate now = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
    String datePath = now.getYear() + "/" + String.format("%02d", now.getMonthValue()) + "/"
        + String.format("%02d", now.getDayOfMonth());
    String ts = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
    return base + "/" + datePath + "/" + idPath + "/" + kind + "-" + ts + "-" + UUID.randomUUID() + ext;
  }
}
