package com.helpinminutes.api.users.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

class EmailVerificationServiceTest {

  @SuppressWarnings("unchecked")
  @Test
  void sendsAndVerifiesAHashedSixDigitOtp() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    JavaMailSender mailSender = mock(JavaMailSender.class);
    AppProperties props = mock(AppProperties.class);
    when(props.otp()).thenReturn(new AppProperties.Otp(300, true));
    when(redis.opsForValue()).thenReturn(values);

    AtomicReference<String> storedHash = new AtomicReference<>();
    doAnswer(invocation -> {
      String key = invocation.getArgument(0);
      if (key.startsWith("him:email_otp:")) storedHash.set(invocation.getArgument(1));
      return null;
    }).when(values).set(anyString(), anyString(), any(Duration.class));
    when(values.get(anyString())).thenAnswer(invocation -> {
      String key = invocation.getArgument(0);
      return key.startsWith("him:email_otp_attempts:") ? null : storedHash.get();
    });

    EmailVerificationService service = new EmailVerificationService(redis, props, mailSender);
    ReflectionTestUtils.setField(service, "fromAddress", "support@superherooo.com");

    String otp = service.sendVerificationEmail("Employee@Facebook.com");
    assertNotNull(otp);
    assertTrue(otp.matches("\\d{6}"));
    assertNotNull(storedHash.get());
    assertFalse(storedHash.get().contains(otp));
    assertTrue(service.verifyEmailOtp("employee@facebook.com", otp));
  }
}
