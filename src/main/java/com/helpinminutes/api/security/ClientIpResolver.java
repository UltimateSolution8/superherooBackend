package com.helpinminutes.api.security;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIpResolver {
  private ClientIpResolver() {}

  public static String resolve(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    if (isTrustedProxy(remote)) {
      String real = request.getHeader("X-Real-IP");
      if (real != null && !real.isBlank()) return limit(real.trim());
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null) {
        String[] chain = forwarded.split(",");
        for (int i = chain.length - 1; i >= 0; i--) {
          String candidate = chain[i].trim();
          if (!candidate.isBlank() && !isTrustedProxy(candidate)) return limit(candidate);
        }
      }
    }
    return limit(remote == null ? "unknown" : remote);
  }

  static boolean isTrustedProxy(String ip) {
    if (ip == null) return false;
    String value = ip.trim();
    if (value.equals("127.0.0.1") || value.equals("::1") || value.startsWith("10.") || value.startsWith("192.168.")) return true;
    if (!value.startsWith("172.")) return false;
    String[] parts = value.split("\\.");
    try { return parts.length > 1 && Integer.parseInt(parts[1]) >= 16 && Integer.parseInt(parts[1]) <= 31; }
    catch (NumberFormatException e) { return false; }
  }

  private static String limit(String value) { return value.length() <= 64 ? value : value.substring(0, 64); }
}
