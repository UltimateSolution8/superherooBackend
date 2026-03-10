package com.helpinminutes.api.kyc.zego;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.ZegoProperties;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class ZegoTokenService {
  private static final String VERSION_FLAG = "04";
  private static final int IV_LENGTH = 16;
  private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

  private final ZegoProperties props;
  private final ObjectMapper mapper;
  private final SecureRandom secureRandom = new SecureRandom();
  private final Random nonceRandom = new Random();

  public ZegoTokenService(ZegoProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
  }

  public String generateToken(String userId, long effectiveSeconds, String payloadJson) {
    long appId = props.appId() == null ? 0L : props.appId();
    if (appId == 0L) {
      throw new IllegalStateException("ZEGO_APP_ID is not configured");
    }
    String secret = props.serverSecret();
    if (secret == null || secret.length() != 32) {
      throw new IllegalStateException("ZEGO_SERVER_SECRET must be 32 characters");
    }
    if (userId == null || userId.isBlank() || userId.length() > 64) {
      throw new IllegalArgumentException("userId must be 1-64 characters");
    }

    long now = System.currentTimeMillis() / 1000;
    long expireTime = now + Math.max(60, effectiveSeconds);
    int nonce = nonceRandom.nextInt();
    String payload = payloadJson == null ? "" : payloadJson;

    try {
      String content = mapper.writeValueAsString(Map.of(
          "app_id", appId,
          "user_id", userId,
          "ctime", now,
          "expire", expireTime,
          "nonce", nonce,
          "payload", payload));

      byte[] ivBytes = new byte[IV_LENGTH];
      secureRandom.nextBytes(ivBytes);
      byte[] contentBytes = encrypt(content.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8), ivBytes);

      ByteBuffer buffer = ByteBuffer.wrap(new byte[contentBytes.length + IV_LENGTH + 12]);
      buffer.order(ByteOrder.BIG_ENDIAN);
      buffer.putLong(expireTime);
      packBytes(ivBytes, buffer);
      packBytes(contentBytes, buffer);

      return VERSION_FLAG + Base64.getEncoder().encodeToString(buffer.array());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate Zego token", e);
    }
  }

  public String generateToken(String userId, long effectiveSeconds) {
    return generateToken(userId, effectiveSeconds, "");
  }

  public String generateToken(String userId) {
    return generateToken(userId, props.tokenTtlSeconds(), "");
  }

  public String generateToken(String userId, String roomId, String userName, long effectiveSeconds) {
    try {
      String payload = mapper.writeValueAsString(Map.of(
          "room_id", roomId == null ? "" : roomId,
          "user_name", userName == null ? "" : userName));
      return generateToken(userId, effectiveSeconds, payload);
    } catch (Exception e) {
      return generateToken(userId, effectiveSeconds, "");
    }
  }

  private static byte[] encrypt(byte[] content, byte[] secretKey, byte[] ivBytes) throws Exception {
    if (secretKey == null || secretKey.length != 32) {
      throw new IllegalArgumentException("secret key length must be 32 bytes");
    }
    if (ivBytes == null || ivBytes.length != 16) {
      throw new IllegalArgumentException("iv length must be 16 bytes");
    }
    if (content == null) {
      content = new byte[] {};
    }
    SecretKeySpec key = new SecretKeySpec(secretKey, "AES");
    IvParameterSpec iv = new IvParameterSpec(ivBytes);
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, key, iv);
    return cipher.doFinal(content);
  }

  private static void packBytes(byte[] buffer, ByteBuffer target) {
    target.putShort((short) buffer.length);
    target.put(buffer);
  }
}
