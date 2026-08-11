package com.helpinminutes.api.helpers.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts full payout account numbers. Plaintext must never leave this boundary. */
@Component
public class BankAccountCipher {
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private final Map<String, SecretKey> keys;
  private final String activeKeyId;
  private final SecureRandom random;

  public record EncryptedValue(String keyId, String ciphertext) {}

  @Autowired
  public BankAccountCipher(
      @Value("${banking.encryption-keys:}") String configuredKeys,
      @Value("${banking.active-key-id:}") String activeKeyId) {
    this(parseKeys(configuredKeys), activeKeyId, new SecureRandom());
  }

  BankAccountCipher(Map<String, SecretKey> keys, String activeKeyId, SecureRandom random) {
    this.keys = Map.copyOf(keys);
    this.activeKeyId = activeKeyId == null ? "" : activeKeyId.trim();
    this.random = random;
    if (this.activeKeyId.isBlank() || !this.keys.containsKey(this.activeKeyId)) {
      throw new IllegalStateException(
          "BANK_ACCOUNT_ACTIVE_KEY_ID must identify a key in BANK_ACCOUNT_ENCRYPTION_KEYS");
    }
  }

  public EncryptedValue encrypt(UUID accountId, String accountNumber) {
    if (accountId == null || accountNumber == null || accountNumber.isBlank()) {
      throw new IllegalArgumentException("Account id and number are required");
    }
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(accountId));
      byte[] encrypted = cipher.doFinal(accountNumber.getBytes(StandardCharsets.UTF_8));
      byte[] payload = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, payload, 0, iv.length);
      System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
      return new EncryptedValue(activeKeyId, "v1:" + Base64.getEncoder().encodeToString(payload));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to protect bank account details", e);
    }
  }

  public String decrypt(UUID accountId, String keyId, String encodedCiphertext) {
    SecretKey key = keys.get(keyId);
    if (key == null) throw new IllegalStateException("Bank account encryption key is unavailable");
    if (encodedCiphertext == null || !encodedCiphertext.startsWith("v1:")) {
      throw new IllegalArgumentException("Unsupported bank account ciphertext version");
    }
    byte[] payload;
    try {
      payload = Base64.getDecoder().decode(encodedCiphertext.substring(3));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid bank account ciphertext", e);
    }
    if (payload.length <= IV_BYTES) throw new IllegalArgumentException("Invalid bank account ciphertext");
    byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_BYTES);
    byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_BYTES, payload.length);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(accountId));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (AEADBadTagException e) {
      throw new SecurityException("Bank account ciphertext authentication failed", e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to read bank account details", e);
    }
  }

  private static byte[] aad(UUID accountId) {
    return ("superheroo:payout-account:" + accountId).getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, SecretKey> parseKeys(String configured) {
    Map<String, SecretKey> parsed = new LinkedHashMap<>();
    if (configured != null) {
      for (String entry : configured.split(",")) {
        String item = entry.trim();
        if (item.isEmpty()) continue;
        int separator = item.indexOf(':');
        if (separator <= 0 || separator == item.length() - 1) {
          throw new IllegalStateException("BANK_ACCOUNT_ENCRYPTION_KEYS entries must be keyId:base64Key");
        }
        String keyId = item.substring(0, separator).trim();
        if (!keyId.matches("^[A-Za-z0-9._-]{1,32}$")) {
          throw new IllegalStateException("Bank account encryption key ids must contain 1-32 safe characters");
        }
        byte[] raw;
        try {
          raw = Base64.getDecoder().decode(item.substring(separator + 1).trim());
        } catch (IllegalArgumentException e) {
          throw new IllegalStateException("Invalid base64 bank account encryption key for " + keyId, e);
        }
        if (raw.length != 32) {
          throw new IllegalStateException("Bank account encryption key " + keyId + " must be exactly 32 bytes");
        }
        if (parsed.putIfAbsent(keyId, new SecretKeySpec(raw, "AES")) != null) {
          throw new IllegalStateException("Duplicate bank account encryption key id: " + keyId);
        }
      }
    }
    if (parsed.isEmpty()) {
      throw new IllegalStateException(
          "BANK_ACCOUNT_ENCRYPTION_KEYS is required; generate a key with `openssl rand -base64 32`");
    }
    return parsed;
  }
}
