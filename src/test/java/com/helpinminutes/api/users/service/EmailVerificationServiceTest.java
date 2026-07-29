package com.helpinminutes.api.users.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class EmailVerificationServiceTest {

  @SuppressWarnings("unchecked")
  @Test
  void sendsAndVerifiesThroughMojoAuthWithoutExposingOtp() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    MojoAuthClient mojo = mock(MojoAuthClient.class);
    AppProperties props = mock(AppProperties.class);
    when(props.otp()).thenReturn(new AppProperties.Otp(300, true));
    when(redis.opsForValue()).thenReturn(values);
    when(mojo.isConfigured()).thenReturn(true);
    when(mojo.sendEmailOtp("employee@facebook.com")).thenReturn("state-123");
    when(values.get("him:email_otp:employee@facebook.com")).thenReturn("mojo:state-123");
    when(mojo.verifyEmailOtp("state-123", "123456")).thenReturn(true);

    EmailVerificationService service = new EmailVerificationService(redis, props, mojo);
    assertNull(service.sendVerificationEmail("Employee@Facebook.com"));
    verify(values).set(anyString(), anyString(), any(Duration.class));
    assertTrue(service.verifyEmailOtp("employee@facebook.com", "123456"));
    verify(redis).delete("him:email_otp:employee@facebook.com");
  }
}
