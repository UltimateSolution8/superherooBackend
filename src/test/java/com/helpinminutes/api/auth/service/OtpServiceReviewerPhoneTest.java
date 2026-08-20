package com.helpinminutes.api.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.ExotelProperties;
import com.helpinminutes.api.config.Msg91Properties;
import com.helpinminutes.api.config.ReviewerPhoneProperties;
import com.helpinminutes.api.config.TwilioProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Google Play reviewers cannot receive an Indian SMS, so a small allowlist of numbers
 * has its OTP provisioned instead of texted.
 *
 * <p>The line that matters: this changes <em>which value is stored</em>, and nothing
 * else. Verification is the same comparison against the same Redis key with the same
 * attempt cap. What has to be pinned down is that the allowlist is narrow, off by
 * default, and confers nothing on anybody outside it — this is the same area of the
 * code as the reviewer bypass that {@link OtpServiceStaticOtpTest} exists to keep shut.
 */
class OtpServiceReviewerPhoneTest {

  private static final String REVIEWER = "9000000101";
  private static final String CODE = "472913";
  private static final String ORDINARY = "9876543210";

  private final Map<String, String> redisStore = new HashMap<>();

  @SuppressWarnings("unchecked")
  private OtpService serviceWith(ReviewerPhoneProperties reviewers) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenAnswer(i -> redisStore.get(i.<String>getArgument(0)));
    when(values.increment(anyString())).thenReturn(1L);
    org.mockito.Mockito.doAnswer(i -> {
      redisStore.put(i.getArgument(0), i.getArgument(1));
      return null;
    }).when(values).set(anyString(), anyString(), any(Duration.class));

    AppProperties props = mock(AppProperties.class);
    when(props.otp()).thenReturn(new AppProperties.Otp(300));

    return new OtpService(redis, props, mock(TwilioProperties.class), mock(ExotelProperties.class),
        mock(Msg91Properties.class), reviewers, Runnable::run);
  }

  private static ReviewerPhoneProperties allowlist() {
    return new ReviewerPhoneProperties("true", REVIEWER + ":" + CODE + ",9000000102:658204");
  }

  @Test
  void anAllowlistedNumberGetsItsConfiguredCode() {
    OtpService service = serviceWith(allowlist());

    assertEquals(CODE, service.startOtp(REVIEWER, "sms"));
    assertTrue(service.verifyOtp(REVIEWER, CODE));
  }

  @Test
  void theConfiguredCodeIsTheOnlyOneThatWorksForThatNumber() {
    OtpService service = serviceWith(allowlist());
    service.startOtp(REVIEWER, "sms");

    assertFalse(service.verifyOtp(REVIEWER, "123456"));
    assertFalse(service.verifyOtp(REVIEWER, "000000"));
    assertFalse(service.verifyOtp(REVIEWER, "658204"), "another reviewer's code must not work here");
  }

  @Test
  void anOrdinaryNumberIsUntouchedByTheAllowlist() {
    OtpService service = serviceWith(allowlist());

    String issued = service.startOtp(ORDINARY, "sms");

    assertNotEquals(CODE, issued);
    assertEquals(6, issued.length());
    assertFalse(service.verifyOtp(ORDINARY, CODE), "the reviewer code must not work anywhere else");
    assertTrue(service.verifyOtp(ORDINARY, issued));
  }

  @Test
  void theAllowlistIsEmptyUnlessBothEnvVarsAreSet() {
    // The seed flag alone.
    assertTrue(new ReviewerPhoneProperties("true", null).isEmpty());
    assertTrue(new ReviewerPhoneProperties("true", "").isEmpty());
    // The allowlist alone: REVIEWER_SEED_ENABLED is the single switch that turns the
    // whole review arrangement off once Play review completes.
    assertTrue(new ReviewerPhoneProperties(null, REVIEWER + ":" + CODE).isEmpty());
    assertTrue(new ReviewerPhoneProperties("false", REVIEWER + ":" + CODE).isEmpty());
    assertFalse(allowlist().isEmpty());
  }

  @Test
  void malformedOrWeakEntriesAreIgnoredRatherThanAccepted() {
    ReviewerPhoneProperties parsed = new ReviewerPhoneProperties("true", String.join(",",
        "9000000101:472913",   // good
        "9000000102",          // no code
        "9000000103:1234",     // too short — would weaken the same comparison every user faces
        "9000000104:abcdef",   // not numeric
        "1234567890:472913")); // not an Indian mobile

    assertEquals("472913", parsed.codeFor("9000000101"));
    for (String rejected : new String[] {"9000000102", "9000000103", "9000000104", "1234567890"}) {
      org.junit.jupiter.api.Assertions.assertNull(parsed.codeFor(rejected));
    }
  }

  @Test
  void theNumberMatchesHoweverTheClientFormatsIt() {
    ReviewerPhoneProperties parsed = allowlist();

    assertEquals(CODE, parsed.codeFor("9000000101"));
    assertEquals(CODE, parsed.codeFor("+919000000101"));
    assertEquals(CODE, parsed.codeFor("919000000101"));
    assertEquals(CODE, parsed.codeFor(" 90000 00101 "));
  }

  @Test
  void withNoAllowlistEveryNumberGetsARandomCode() {
    OtpService service = serviceWith(new ReviewerPhoneProperties(null, null));

    String first = service.startOtp(REVIEWER, "sms");

    assertNotEquals(CODE, first);
    assertFalse(service.verifyOtp(REVIEWER, CODE));
  }
}
