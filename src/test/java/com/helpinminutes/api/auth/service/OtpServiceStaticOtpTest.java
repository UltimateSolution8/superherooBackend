package com.helpinminutes.api.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.ExotelProperties;
import com.helpinminutes.api.config.TwilioProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Regression guard. A universal static OTP ("123456"/"1234") was accepted for any
 * phone whenever app.otp.returnOtpInResponse was true — and that flag defaulted to
 * true, so it was live in production. These tests pin the bypass shut even with the
 * dev flag deliberately enabled.
 */
class OtpServiceStaticOtpTest {

  @SuppressWarnings("unchecked")
  private OtpService serviceWithStoredOtp(String storedOtp) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenReturn(storedOtp);

    AppProperties props = mock(AppProperties.class);
    // Dev flag ON — the worst case. Verification must still be strict.
    when(props.otp()).thenReturn(new AppProperties.Otp(300, true));

    return new OtpService(redis, props, mock(TwilioProperties.class), mock(ExotelProperties.class),
        Runnable::run);
  }

  @Test
  void rejectsUniversalStaticOtpEvenWhenDevFlagEnabled() {
    OtpService service = serviceWithStoredOtp("871345");

    assertFalse(service.verifyOtp("9876543210", "123456"),
        "static 123456 must never be accepted");
    assertFalse(service.verifyOtp("9876543210", "1234"),
        "static 1234 must never be accepted");
    assertFalse(service.verifyOtp("9999999991", "123456"),
        "static OTP must not be accepted for former reviewer phones either");
  }

  @Test
  void stillAcceptsTheGenuinelyIssuedOtp() {
    OtpService service = serviceWithStoredOtp("871345");
    assertTrue(service.verifyOtp("9876543210", "871345"));
  }
}
