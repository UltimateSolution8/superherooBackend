package com.helpinminutes.api.kyc.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.ZegoProperties;
import com.helpinminutes.api.kyc.service.KycService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kyc/recording")
public class ZegoRecordingCallbackController {
  private final KycService kycService;
  private final ZegoProperties props;
  private final ObjectMapper mapper;

  public ZegoRecordingCallbackController(KycService kycService, ZegoProperties props, ObjectMapper mapper) {
    this.kycService = kycService;
    this.props = props;
    this.mapper = mapper;
  }

  @PostMapping("/callback")
  public ResponseEntity<Void> callback(@RequestBody String body) {
    try {
      JsonNode payload = mapper.readTree(body);
      String signature = payload.path("signature").asText("");
      String timestamp = payload.path("timestamp").asText("");
      String nonce = payload.path("nonce").asText("");
      if (!verifySignature(signature, timestamp, nonce)) {
        return ResponseEntity.status(403).build();
      }

      int eventType = payload.path("event_type").asInt(-1);
      if (eventType == 1 || eventType == 5) {
        String roomId = payload.path("room_id").asText(null);
        String taskId = payload.path("task_id").asText(null);
        JsonNode detail = payload.path("detail");
        String fileUrl = null;
        JsonNode fileInfo = detail.path("file_info");
        if (fileInfo.isArray() && fileInfo.size() > 0) {
          fileUrl = fileInfo.get(0).path("file_url").asText(null);
        }
        if (fileUrl != null && !fileUrl.isBlank()) {
          kycService.attachLiveRecording(roomId, taskId, fileUrl);
        }
      }

      return ResponseEntity.ok().build();
    } catch (Exception e) {
      return ResponseEntity.badRequest().build();
    }
  }

  private boolean verifySignature(String signature, String timestamp, String nonce) {
    if (props.callbackSecret() == null || props.callbackSecret().isBlank()) {
      return true;
    }
    if (signature == null || signature.isBlank()) {
      return false;
    }
    String[] parts = { props.callbackSecret(), timestamp == null ? "" : timestamp, nonce == null ? "" : nonce };
    Arrays.sort(parts);
    StringBuilder sb = new StringBuilder();
    for (String p : parts) sb.append(p);
    String expected = sha1Hex(sb.toString());
    return expected.equals(signature);
  }

  private static String sha1Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }
}
