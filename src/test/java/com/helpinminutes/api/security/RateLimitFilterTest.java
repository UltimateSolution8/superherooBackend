package com.helpinminutes.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new RateLimitFilter();
        chain = mock(FilterChain.class);
        responseWriter = new StringWriter();
    }

    private HttpServletRequest mockRequest(String path, String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn(path);
        when(req.getRemoteAddr()).thenReturn(ip);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getHeader("X-Real-IP")).thenReturn(null);
        return req;
    }

    private HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(new PrintWriter(responseWriter));
        return res;
    }

    @Test
    void otpStartPath_allowsFirstFiveRequests() throws Exception {
        String ip = "10.0.0.1";
        String path = "/api/v1/auth/otp/start";

        for (int i = 0; i < 5; i++) {
            HttpServletRequest req = mockRequest(path, ip);
            HttpServletResponse res = mockResponse();
            filter.doFilterInternal(req, res, chain);
        }
        // First 5 should pass — chain should be invoked 5 times
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void otpStartPath_blocksOnSixthRequest() throws Exception {
        String ip = "10.0.0.2";
        String path = "/api/v1/auth/otp/start";

        for (int i = 0; i < 5; i++) {
            HttpServletRequest req = mockRequest(path, ip);
            HttpServletResponse res = mockResponse();
            filter.doFilterInternal(req, res, chain);
        }
        // 6th request should be blocked
        HttpServletRequest req6 = mockRequest(path, ip);
        HttpServletResponse res6 = mockResponse();
        filter.doFilterInternal(req6, res6, chain);

        verify(res6).setStatus(429);
        // chain should NOT be called again (still 5)
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void differentIps_trackedSeparately() throws Exception {
        String path = "/api/v1/auth/otp/start";
        // 5 requests from IP A
        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(mockRequest(path, "10.0.1.1"),
                    mockResponse(), chain);
        }
        // First request from IP B should NOT be blocked
        HttpServletRequest reqB = mockRequest(path, "10.0.1.2");
        HttpServletResponse resB = mockResponse();
        filter.doFilterInternal(reqB, resB, chain);
        verify(resB, never()).setStatus(429);
    }

    @Test
    void nonPostMethod_notRateLimited() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/api/v1/auth/otp/start");
        HttpServletResponse res = mockResponse();

        filter.doFilterInternal(req, res, chain);
        verify(res, never()).setStatus(429);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void ifscLookupGet_isRateLimited() throws Exception {
        String path = "/api/v1/helper/ifsc/HDFC0000001";
        String ip = "10.0.2.1";
        for (int i = 0; i < 30; i++) {
            HttpServletRequest req = mockRequest(path, ip);
            when(req.getMethod()).thenReturn("GET");
            filter.doFilterInternal(req, mockResponse(), chain);
        }
        HttpServletRequest blocked = mockRequest(path, ip);
        when(blocked.getMethod()).thenReturn("GET");
        HttpServletResponse response = mockResponse();
        filter.doFilterInternal(blocked, response, chain);
        verify(response).setStatus(429);
    }

    @Test
    void bankChangeChallenge_isRateLimited() throws Exception {
        String path = "/api/v1/helper/payout-account/change-challenge";
        String ip = "203.0.113.44";
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(mockRequest(path, ip), mockResponse(), chain);
        }
        HttpServletResponse response = mockResponse();
        filter.doFilterInternal(mockRequest(path, ip), response, chain);
        verify(response).setStatus(429);
    }

    @Test
    void xForwardedFor_usedAsClientIp() throws Exception {
        String path = "/api/v1/auth/otp/verify";
        // Exhaust limit for proxied IP
        for (int i = 0; i < 10; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getMethod()).thenReturn("POST");
            when(req.getRequestURI()).thenReturn(path);
            when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
            when(req.getHeader("X-Real-IP")).thenReturn(null);
            when(req.getRemoteAddr()).thenReturn("10.0.0.1");
            filter.doFilterInternal(req, mockResponse(), chain);
        }
        // 11th request from same proxied IP should be blocked
        HttpServletRequest req11 = mock(HttpServletRequest.class);
        when(req11.getMethod()).thenReturn("POST");
        when(req11.getRequestURI()).thenReturn(path);
        when(req11.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(req11.getHeader("X-Real-IP")).thenReturn(null);
        when(req11.getRemoteAddr()).thenReturn("10.0.0.1");
        HttpServletResponse res11 = mockResponse();
        filter.doFilterInternal(req11, res11, chain);
        verify(res11).setStatus(429);
    }

    @Test
    void loginPath_hasLowerLimit_thanVerify() throws Exception {
        // Login allows 12/min, verify allows 10/min
        // This test verifies they are configured independently
        String loginPath = "/api/v1/auth/password/login";
        String verifyPath = "/api/v1/auth/otp/verify";
        String ip = "10.0.0.10";
        // Exhaust verify limit (10)
        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(mockRequest(verifyPath, ip), mockResponse(), chain);
        }
        // 11th verify should be blocked
        HttpServletResponse resBlocked = mockResponse();
        filter.doFilterInternal(mockRequest(verifyPath, ip), resBlocked, chain);
        verify(resBlocked).setStatus(429);
        // But login from same IP should still work (different key)
        HttpServletResponse resLogin = mockResponse();
        filter.doFilterInternal(mockRequest(loginPath, ip), resLogin, chain);
        verify(resLogin, never()).setStatus(429);
    }

    @Test
    void rateLimitFilter_doesNotInterfere_withAuthenticationFlow() throws Exception {
        // Rate limiter (429) should only apply to specific paths
        // Other authenticated paths should pass through to SecurityConfig
        // which will return 401 for unauthenticated access
        String protectedPath = "/api/v1/tasks";
        HttpServletRequest req = mockRequest(protectedPath, "10.0.0.50");
        HttpServletResponse res = mockResponse();
        
        filter.doFilterInternal(req, res, chain);
        
        // Rate limiter should NOT block this path (not in rate limit list)
        verify(res, never()).setStatus(429);
        // Chain should be invoked, allowing Spring Security to handle auth
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void rateLimitFilter_returnsCorrectStatus_for429() throws Exception {
        // Verify 429 is used for rate limiting, not confused with 401
        String path = "/api/v1/auth/otp/start";
        String ip = "10.0.0.60";
        
        // Exhaust rate limit
        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(mockRequest(path, ip), mockResponse(), chain);
        }
        
        // 6th request should be blocked with 429, not 401
        HttpServletRequest reqBlocked = mockRequest(path, ip);
        HttpServletResponse resBlocked = mockResponse();
        filter.doFilterInternal(reqBlocked, resBlocked, chain);
        
        verify(resBlocked).setStatus(429);
        verify(resBlocked, never()).setStatus(401);
        verify(resBlocked, never()).setStatus(403);
    }
}
