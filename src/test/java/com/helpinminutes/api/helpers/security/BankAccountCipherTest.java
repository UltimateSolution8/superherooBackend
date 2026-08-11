package com.helpinminutes.api.helpers.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class BankAccountCipherTest {
  private static SecretKey key(int seed) {
    byte[] raw = new byte[32];
    java.util.Arrays.fill(raw, (byte) seed);
    return new SecretKeySpec(raw, "AES");
  }

  @Test
  void encryptsWithRandomIvAndDecryptsAcrossRotatedKeys() {
    UUID accountId = UUID.randomUUID();
    BankAccountCipher oldCipher = new BankAccountCipher(Map.of("v1", key(1)), "v1", new SecureRandom());
    var first = oldCipher.encrypt(accountId, "12345678901234");
    var second = oldCipher.encrypt(accountId, "12345678901234");
    assertNotEquals(first.ciphertext(), second.ciphertext());

    BankAccountCipher rotated = new BankAccountCipher(Map.of("v1", key(1), "v2", key(2)), "v2", new SecureRandom());
    assertEquals("12345678901234", rotated.decrypt(accountId, first.keyId(), first.ciphertext()));
    assertEquals("v2", rotated.encrypt(accountId, "12345678901234").keyId());
  }

  @Test
  void rejectsTamperingWrongAccountAndMissingKey() {
    UUID accountId = UUID.randomUUID();
    BankAccountCipher cipher = new BankAccountCipher(Map.of("v1", key(1)), "v1", new SecureRandom());
    var encrypted = cipher.encrypt(accountId, "12345678901234");
    String tampered = encrypted.ciphertext().substring(0, encrypted.ciphertext().length() - 2) + "AA";
    assertThrows(SecurityException.class, () -> cipher.decrypt(accountId, encrypted.keyId(), tampered));
    assertThrows(SecurityException.class, () -> cipher.decrypt(UUID.randomUUID(), encrypted.keyId(), encrypted.ciphertext()));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt(accountId, "missing", encrypted.ciphertext()));
  }
}
