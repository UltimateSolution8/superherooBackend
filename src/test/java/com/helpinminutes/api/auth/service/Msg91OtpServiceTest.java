package com.helpinminutes.api.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.ExotelProperties;
import com.helpinminutes.api.config.Msg91Properties;
import com.helpinminutes.api.config.ReviewerPhoneProperties;
import com.helpinminutes.api.config.TwilioProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class Msg91OtpServiceTest {

  private StringRedisTemplate redis;
  private ValueOperations<String, String> values;
  private AppProperties props;
  private Msg91Properties msg91;
  private OtpService otpService;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);

    props = mock(AppProperties.class);
    when(props.otp()).thenReturn(new AppProperties.Otp(300));

    // Deliberately fake. A live MSG91 auth key sat here in plain text; it is a
    // credential, and a credential in a test file is a credential in the repository.
    msg91 = new Msg91Properties(
        true,
        "test-auth-key",
        "test-template-id",
        "SPHROO",
        "test-dlt-template-id",
        5);

    otpService = new OtpService(
        redis,
        props,
        mock(TwilioProperties.class),
        mock(ExotelProperties.class),
        msg91,
        new ReviewerPhoneProperties(null, null),
        Runnable::run);
  }

  @Test
  void generatesAndStoresOtpInRedisWhenMsg91Enabled() {
    String phone = "8208024055";
    String otp = otpService.startOtp(phone, "sms");

    assertNotNull(otp);
    assertEquals(6, otp.length());
    verify(values).set(eq("him:otp:" + phone), eq(otp), eq(Duration.ofSeconds(300)));
  }

  @Test
  void verifiesStoredOtpSuccessfully() {
    String phone = "8208024055";
    when(values.get("him:otp:" + phone)).thenReturn("654321");

    boolean verified = otpService.verifyOtp(phone, "654321");
    assertTrue(verified);
    verify(redis).delete("him:otp:" + phone);
  }
}
