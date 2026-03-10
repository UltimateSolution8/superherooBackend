package com.helpinminutes.api.kyc.zego;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.helpinminutes.api.config.ZegoProperties;
import com.helpinminutes.api.errors.BadRequestException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ZegoCloudRecordingService {
  private final ZegoProperties props;
  private final ObjectMapper mapper;
  private final HttpClient httpClient;
  private final String s3Endpoint;
  private final String s3Bucket;
  private final String s3KeyId;
  private final String s3KeySecret;
  private final String s3Region;
  private final String callbackUrl;

  public ZegoCloudRecordingService(
      ZegoProperties props,
      ObjectMapper mapper,
      @Value("${SUPABASE_S3_ENDPOINT:}") String s3Endpoint,
      @Value("${SUPABASE_S3_BUCKET:}") String s3Bucket,
      @Value("${SUPABASE_S3_ACCESS_KEY_ID:}") String s3KeyId,
      @Value("${SUPABASE_S3_ACCESS_KEY_SECRET:}") String s3KeySecret,
      @Value("${SUPABASE_S3_REGION:}") String s3Region,
      @Value("${ZEGO_RECORDING_CALLBACK_URL:}") String callbackUrl) {
    this.props = props;
    this.mapper = mapper;
    this.s3Endpoint = s3Endpoint == null ? "" : s3Endpoint.trim();
    this.s3Bucket = s3Bucket == null ? "" : s3Bucket.trim();
    this.s3KeyId = s3KeyId == null ? "" : s3KeyId.trim();
    this.s3KeySecret = s3KeySecret == null ? "" : s3KeySecret.trim();
    this.s3Region = s3Region == null ? "" : s3Region.trim();
    this.callbackUrl = callbackUrl == null ? "" : callbackUrl.trim();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public StartRecordResult startRecording(String roomId) {
    if (roomId == null || roomId.isBlank()) {
      throw new BadRequestException("Missing roomId for recording");
    }
    ensureStorageConfigured();
    String url = buildSignedUrl("StartRecord");

    ObjectNode body = mapper.createObjectNode();
    body.put("RoomId", roomId);

    ObjectNode input = body.putObject("RecordInputParams");
    input.put("RecordMode", 2);
    input.put("StreamType", 3);
    input.put("MaxIdleTime", 60);

    ObjectNode output = body.putObject("RecordOutputParams");
    output.put("OutputFileFormat", "mp4");
    output.put("OutputFolder", "kyc-recordings");
    if (!callbackUrl.isBlank()) {
      output.put("CallbackUrl", callbackUrl);
    }

    ObjectNode storage = body.putObject("StorageParams");
    storage.put("Vendor", 10);
    storage.put("Region", s3Region);
    storage.put("Bucket", s3Bucket);
    storage.put("AccessKeyId", s3KeyId);
    storage.put("AccessKeySecret", s3KeySecret);
    storage.put("EndPoint", s3Endpoint);

    JsonNode response = postJson(url, body);
    String taskId = response.path("Data").path("TaskId").asText(null);
    if (taskId == null || taskId.isBlank()) {
      throw new BadRequestException("Failed to start recording");
    }
    return new StartRecordResult(taskId, response.path("RequestId").asText(null));
  }

  public void stopRecording(String taskId) {
    if (taskId == null || taskId.isBlank()) {
      return;
    }
    String url = buildSignedUrl("StopRecord");
    ObjectNode body = mapper.createObjectNode();
    body.put("TaskId", taskId);
    postJson(url, body);
  }

  private String buildSignedUrl(String action) {
    long appId = props.appId() == null ? 0L : props.appId();
    if (appId == 0L) {
      throw new BadRequestException("ZEGO_APP_ID not configured");
    }
    String secret = props.serverSecret();
    if (secret == null || secret.isBlank()) {
      throw new BadRequestException("ZEGO_SERVER_SECRET not configured");
    }
    String nonce = UUID.randomUUID().toString().replace("-", "");
    long timestamp = System.currentTimeMillis() / 1000;
    String signature = md5(appId + nonce + secret + timestamp);

    String base = props.recordApiBaseUrl().endsWith("/")
        ? props.recordApiBaseUrl().substring(0, props.recordApiBaseUrl().length() - 1)
        : props.recordApiBaseUrl();

    return base + "/?Action=" + encode(action)
        + "&AppId=" + appId
        + "&SignatureNonce=" + encode(nonce)
        + "&Timestamp=" + timestamp
        + "&SignatureVersion=2.0"
        + "&Signature=" + encode(signature);
  }

  private JsonNode postJson(String url, ObjectNode body) {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
          .timeout(Duration.ofSeconds(20))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new BadRequestException("Recording API failed: " + response.statusCode());
      }
      return mapper.readTree(response.body());
    } catch (Exception e) {
      throw new BadRequestException("Recording API error: " + e.getMessage());
    }
  }

  private void ensureStorageConfigured() {
    if (s3Endpoint.isBlank() || s3Bucket.isBlank() || s3KeyId.isBlank() || s3KeySecret.isBlank()) {
      throw new BadRequestException("S3 storage is not configured for recording");
    }
  }

  private static String md5(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("MD5 failure", e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record StartRecordResult(String taskId, String requestId) {}
}
