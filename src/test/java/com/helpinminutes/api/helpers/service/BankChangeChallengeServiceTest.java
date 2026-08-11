package com.helpinminutes.api.helpers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.auth.service.OtpService;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class BankChangeChallengeServiceTest {
  @Test
  void issuesPurposeBoundChallengeAndSingleUseToken() {
    UUID userId = UUID.randomUUID();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    UserRepository users = mock(UserRepository.class);
    OtpService otp = mock(OtpService.class);
    AppProperties props = mock(AppProperties.class);
    AppProperties.Otp otpProps = new AppProperties.Otp(300, true);
    when(props.otp()).thenReturn(otpProps);
    when(redis.opsForValue()).thenReturn(values);
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setRole(UserRole.HELPER);
    user.setPhone("9876543210");
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(otp.startOtp("9876543210", "sms")).thenReturn("123456");
    BankChangeChallengeService service = new BankChangeChallengeService(redis, users, otp, props);

    var challenge = service.start(userId, UserRole.HELPER);
    assertEquals("••••••3210", challenge.maskedPhone());
    assertEquals("123456", challenge.devOtp());
    String payload = userId + "|HELPER|9876543210";
    when(values.get("him:bank-change:challenge:" + challenge.challengeId())).thenReturn(payload);
    when(otp.verifyOtp("9876543210", "123456")).thenReturn(true);

    var token = service.verify(userId, UserRole.HELPER, challenge.challengeId(), "123456");
    assertTrue(token.changeToken().length() >= 40);
    verify(values).set(eq("him:bank-change:token:" + token.changeToken()),
        eq(userId + "|HELPER|verified"), eq(Duration.ofMinutes(10)));
  }

  @Test
  void rejectsExpiredOrWrongUserChallenge() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    BankChangeChallengeService service = new BankChangeChallengeService(
        redis, mock(UserRepository.class), mock(OtpService.class), mock(AppProperties.class));
    UUID challengeId = UUID.randomUUID();
    when(values.get(any())).thenReturn(UUID.randomUUID() + "|HELPER|9876543210");
    assertThrows(BadRequestException.class,
        () -> service.verify(UUID.randomUUID(), UserRole.HELPER, challengeId, "123456"));
    when(redis.execute(any(), any(java.util.List.class))).thenReturn(null);
    assertThrows(ForbiddenException.class,
        () -> service.consume(UUID.randomUUID(), UserRole.HELPER, "replayed-token"));
  }
}
