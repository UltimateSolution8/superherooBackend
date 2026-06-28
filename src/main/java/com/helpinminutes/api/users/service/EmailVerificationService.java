package com.helpinminutes.api.users.service;

import com.helpinminutes.api.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class EmailVerificationService {
  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final RestTemplate restTemplate;
  
  @Value("${mojoauth.api-key:5ea4db1c-7992-4359-937a-384d5c3fce41}")
  private String mojoAuthApiKey;

  private final ConcurrentHashMap<String, String> localStateIdFallback = new ConcurrentHashMap<>();

  public EmailVerificationService(StringRedisTemplate redis, AppProperties props) {
    this.redis = redis;
    this.props = props;
    this.restTemplate = new RestTemplate();
  }

  public String sendVerificationEmail(String email) {
    String url = "https://api.mojoauth.com/users/emailotp";
    
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-API-Key", mojoAuthApiKey);
    
    Map<String, String> payload = Map.of("email", email);
    HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);
    
    try {
      ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
      if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
        String stateId = (String) response.getBody().get("state_id");
        if (stateId != null) {
          String key = "him:mojo_state_id:" + email.toLowerCase().trim();
          try {
            redis.opsForValue().set(key, stateId, Duration.ofSeconds(props.otp().ttlSeconds()));
          } catch (Exception e) {
            log.warn("Redis state_id write failed, falling back to local cache: {}", e.getMessage());
            localStateIdFallback.put(key, stateId);
          }
          log.info("MojoAuth verification email sent successfully to {}, state_id: {}", email, stateId);
          return null; // OTP is managed by MojoAuth
        }
      }
      log.error("Failed to send MojoAuth email, response: {}", response.getBody());
    } catch (Exception e) {
      log.error("Failed to send verification email to {}: {}", email, e.getMessage());
    }

    return null;
  }

  public boolean verifyEmailOtp(String email, String otp) {
    String key = "him:mojo_state_id:" + email.toLowerCase().trim();
    String stateId = null;
    
    try {
      stateId = redis.opsForValue().get(key);
    } catch (Exception e) {
      log.warn("Redis state_id read failed, falling back to local cache: {}", e.getMessage());
    }

    if (stateId == null) {
      stateId = localStateIdFallback.get(key);
    }

    if (stateId == null) {
      log.warn("No state_id found for email: {}", email);
      return false;
    }

    String url = "https://api.mojoauth.com/users/emailotp/verify";
    
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-API-Key", mojoAuthApiKey);
    
    Map<String, String> payload = Map.of(
        "state_id", stateId,
        "otp", otp
    );
    HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);
    
    try {
      ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
      if (response.getStatusCode().is2xxSuccessful()) {
        log.info("MojoAuth OTP verified successfully for email: {}", email);
        try {
          redis.delete(key);
        } catch (Exception e) {
          log.warn("Redis state_id delete failed: {}", e.getMessage());
        }
        localStateIdFallback.remove(key);
        return true;
      }
    } catch (Exception e) {
      log.error("Failed to verify MojoAuth OTP for email {}: {}", email, e.getMessage());
    }

    return false;
  }
}
