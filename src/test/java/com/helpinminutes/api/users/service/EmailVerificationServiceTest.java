package com.helpinminutes.api.users.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.users.service.email.EmailOtpDispatch;
import com.helpinminutes.api.users.service.email.EmailOtpSender;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class EmailVerificationServiceTest {

  private static final String EMAIL = "employee@facebook.com";
  private static final String STATE_KEY = "him:email_otp:" + EMAIL;
  private static final String RESET_KEY = "him:pwd_reset_otp:" + EMAIL;

  /** Delegated provider: owns the code, hands back a state id. */
  private EmailOtpSender delegatedSender(String stateId, boolean verifyResult) {
    EmailOtpSender sender = mock(EmailOtpSender.class);
    when(sender.providerId()).thenReturn("mojo");
    when(sender.isConfigured()).thenReturn(true);
    when(sender.send(anyString())).thenReturn(EmailOtpDispatch.delegated(stateId));
    when(sender.verify(anyString(), anyString(), anyString())).thenReturn(verifyResult);
    return sender;
  }

  /** Local provider: we own the code. */
  private EmailOtpSender localSender(String otp) {
    EmailOtpSender sender = mock(EmailOtpSender.class);
    when(sender.providerId()).thenReturn("local");
    when(sender.isConfigured()).thenReturn(true);
    when(sender.send(anyString())).thenReturn(EmailOtpDispatch.local(otp));
    when(sender.verify(anyString(), anyString(), anyString()))
        .thenAnswer(inv -> inv.getArgument(1).equals(inv.getArgument(2)));
    return sender;
  }

  @SuppressWarnings("unchecked")
  private ValueOperations<String, String> stubRedis(StringRedisTemplate redis) {
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    // Attempt counter: first guess.
    when(values.increment(anyString())).thenReturn(1L);
    return values;
  }

  private AppProperties props() {
    AppProperties props = mock(AppProperties.class);
    when(props.otp()).thenReturn(new AppProperties.Otp(300));
    return props;
  }

  @Test
  void delegatedProviderVerifiesWithoutExposingTheCode() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = stubRedis(redis);
    EmailOtpSender mojo = delegatedSender("state-123", true);
    when(values.get(STATE_KEY)).thenReturn("mojo:state-123");

    EmailVerificationService service =
        new EmailVerificationService(redis, props(), List.of(mojo));

    // A delegated provider must never hand the plaintext code back to us.
    assertNull(service.sendVerificationEmail("Employee@Facebook.com"));
    verify(values).set(anyString(), anyString(), any(Duration.class));

    assertTrue(service.verifyEmailOtp(EMAIL, "123456"));
    verify(mojo).verify(EMAIL, "state-123", "123456");
    verify(redis).delete(STATE_KEY);
  }

  @Test
  void fallsThroughToTheNextProviderWhenTheFirstFails() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    stubRedis(redis);
    EmailOtpSender mojo = mock(EmailOtpSender.class);
    when(mojo.providerId()).thenReturn("mojo");
    when(mojo.isConfigured()).thenReturn(true);
    when(mojo.send(anyString())).thenReturn(null); // delivery failed
    EmailOtpSender smtp = localSender("864213");

    EmailVerificationService service =
        new EmailVerificationService(redis, props(), List.of(mojo, smtp));

    assertEquals("864213", service.sendVerificationEmail(EMAIL));
    verify(smtp).send(EMAIL);
  }

  @Test
  void skipsUnconfiguredProviders() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    stubRedis(redis);
    EmailOtpSender mojo = mock(EmailOtpSender.class);
    when(mojo.isConfigured()).thenReturn(false);
    EmailOtpSender smtp = localSender("112233");

    EmailVerificationService service =
        new EmailVerificationService(redis, props(), List.of(mojo, smtp));

    assertEquals("112233", service.sendVerificationEmail(EMAIL));
    verify(mojo, never()).send(anyString());
  }

  @Test
  void emailVerificationCodeCannotBeReplayedAsAPasswordReset() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = stubRedis(redis);
    EmailOtpSender smtp = localSender("445566");

    EmailVerificationService service =
        new EmailVerificationService(redis, props(), List.of(smtp));

    service.sendVerificationEmail(EMAIL);
    // Only the VERIFY_EMAIL key was written; the reset key is empty.
    when(values.get(STATE_KEY)).thenReturn("local:445566");
    when(values.get(RESET_KEY)).thenReturn(null);

    assertFalse(service.verifyPasswordResetOtp(EMAIL, "445566"),
        "a code issued to verify an address must not authorise a password reset");
    assertTrue(service.verifyEmailOtp(EMAIL, "445566"));
  }

  @Test
  void burnsTheCodeAfterTooManyFailedAttempts() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = stubRedis(redis);
    when(values.get(STATE_KEY)).thenReturn("local:445566");
    when(values.increment(anyString())).thenReturn(6L); // past the limit of 5

    EmailVerificationService service =
        new EmailVerificationService(redis, props(), List.of(localSender("445566")));

    assertFalse(service.verifyEmailOtp(EMAIL, "445566"),
        "the correct code must still be rejected once the attempt budget is spent");
    verify(redis).delete(STATE_KEY);
  }

  @Test
  void rejectsMalformedCodesWithoutTouchingTheProvider() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    stubRedis(redis);
    EmailOtpSender smtp = localSender("445566");

    EmailVerificationService service =
        new EmailVerificationService(redis, props(), List.of(smtp));

    assertFalse(service.verifyEmailOtp(EMAIL, "not-a-code"));
    assertFalse(service.verifyEmailOtp(EMAIL, ""));
    verify(smtp, never()).verify(anyString(), anyString(), anyString());
  }
}
