package com.helpinminutes.api.storage;

import com.helpinminutes.api.errors.BadRequestException;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class SupabaseStorageService {
  private final String endpoint;
  private final String bucket;
  private final String keyId;
  private final String keySecret;
  private final String region;
  private final String publicBaseUrl;

  public SupabaseStorageService(
      @Value("${SUPABASE_S3_ENDPOINT:}") String endpoint,
      @Value("${SUPABASE_S3_BUCKET:}") String bucket,
      @Value("${SUPABASE_S3_ACCESS_KEY_ID:}") String keyId,
      @Value("${SUPABASE_S3_ACCESS_KEY_SECRET:}") String keySecret,
      @Value("${SUPABASE_S3_REGION:us-east-1}") String region,
      @Value("${SUPABASE_PUBLIC_BASE_URL:}") String publicBaseUrl) {
    this.endpoint = endpoint == null ? "" : endpoint.trim();
    this.bucket = bucket == null ? "" : bucket.trim();
    this.keyId = keyId == null ? "" : keyId.trim();
    this.keySecret = keySecret == null ? "" : keySecret.trim();
    this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
    this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
  }

  public boolean isConfigured() {
    return !endpoint.isBlank() && !bucket.isBlank() && !keyId.isBlank() && !keySecret.isBlank();
  }

  public String uploadHelperKycDoc(UUID helperId, String kind, MultipartFile file) {
    if (!isConfigured()) {
      throw new BadRequestException("Storage is not configured");
    }
    if (file == null || file.isEmpty()) {
      throw new BadRequestException(kind + " file is required");
    }

    String contentType = safeContentType(file.getContentType());
    String ext = detectExtension(contentType, file.getOriginalFilename());
    String key = "helper-kyc/" + helperId + "/" + kind + "-" + UUID.randomUUID() + ext;
    return uploadFile(file, contentType, key, "document");
  }

  public String uploadTaskSelfie(UUID taskId, UUID helperId, String stage, MultipartFile file) {
    if (!isConfigured()) {
      throw new BadRequestException("Storage is not configured");
    }
    if (file == null || file.isEmpty()) {
      throw new BadRequestException("selfie file is required");
    }
    String contentType = safeContentType(file.getContentType());
    String ext = detectExtension(contentType, file.getOriginalFilename());
    String key = "task-selfies/" + taskId + "/" + helperId + "/" + stage + "-" + UUID.randomUUID() + ext;
    return uploadFile(file, contentType, key, "selfie");
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
      throw new BadRequestException("Could not read uploaded file");
    } catch (Exception e) {
      throw new BadRequestException("Could not upload " + fileLabel + " to storage");
    }
  }

  private S3Client s3Client() {
    AwsBasicCredentials creds = AwsBasicCredentials.create(keyId, keySecret);
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .forcePathStyle(true)
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(creds))
        .build();
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
    if (s == null || s.isBlank()) return "";
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
    if (lowerCt.contains("jpeg")) return ".jpg";
    if (lowerCt.contains("png")) return ".png";
    if (lowerCt.contains("pdf")) return ".pdf";

    if (originalName != null) {
      String n = originalName.toLowerCase(Locale.ROOT);
      if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return ".jpg";
      if (n.endsWith(".png")) return ".png";
      if (n.endsWith(".pdf")) return ".pdf";
    }
    return ".bin";
  }
}
