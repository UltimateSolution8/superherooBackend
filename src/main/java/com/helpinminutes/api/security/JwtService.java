package com.helpinminutes.api.security;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final AppProperties props;
  private final SecretKey accessKey;
  private final SecretKey refreshKey;

  public JwtService(AppProperties props) {
    this.props = props;
    this.accessKey = deriveKey(props.jwt().accessSecret());
    this.refreshKey = deriveKey(props.jwt().refreshSecret());
  }

  public String createAccessToken(UserEntity user) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(props.jwt().accessTtlSeconds());
    return Jwts.builder()
        .subject(user.getId().toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .claims(Map.of(
            "role", user.getRole().name(),
            "type", "access"
        ))
        .signWith(accessKey)
        .compact();
  }

  public String createRefreshToken(UserEntity user) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(props.jwt().refreshTtlSeconds());
    return Jwts.builder()
        .subject(user.getId().toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .claims(Map.of(
            "role", user.getRole().name(),
            "type", "refresh"
        ))
        .signWith(refreshKey)
        .compact();
  }

  public UserPrincipal parseAccessToken(String token) {
    Jws<Claims> jws = Jwts.parser().verifyWith(accessKey).build().parseSignedClaims(token);
    Claims c = jws.getPayload();
    if (!"access".equals(c.get("type", String.class))) {
      throw new IllegalArgumentException("Not an access token");
    }
    UUID userId = UUID.fromString(c.getSubject());
    UserRole role = UserRole.valueOf(c.get("role", String.class));
    return new UserPrincipal(userId, role);
  }

  public UserPrincipal parseRefreshToken(String token) {
    Jws<Claims> jws = Jwts.parser().verifyWith(refreshKey).build().parseSignedClaims(token);
    Claims c = jws.getPayload();
    if (!"refresh".equals(c.get("type", String.class))) {
      throw new IllegalArgumentException("Not a refresh token");
    }
    UUID userId = UUID.fromString(c.getSubject());
    UserRole role = UserRole.valueOf(c.get("role", String.class));
    return new UserPrincipal(userId, role);
  }

  private static SecretKey deriveKey(String secret) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] keyBytes = md.digest(secret.getBytes(StandardCharsets.UTF_8));
      return Keys.hmacShaKeyFor(keyBytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
