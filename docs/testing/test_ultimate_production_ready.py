#!/usr/bin/env python3
"""
===================================================================================
ULTIMATE PRODUCTION READINESS TEST SUITE - BILLION DOLLAR APP STANDARD
===================================================================================
This suite tests EVERYTHING with ruthless detail to find ALL possible bugs:
- All 3 apps (Buyer, Helper, Mediator/Admin) with complete flows
- Security: SQL injection, XSS, CSRF, IDOR, authentication bypass
- Performance: Load testing, response times, concurrent users
- Edge cases: Race conditions, timezone bugs, data corruption
- Integration: End-to-end flows from app to backend
- Industry best practices from Urban Company, Uber, TaskRabbit, Snabbit

Target: https://api.mysuperhero.xyz (Production Server: 168.144.64.250)
Author: Ultimate Testing Framework
Last updated: 2026-07-17
===================================================================================
"""

import sys
import time
import json
import uuid
import requests
import concurrent.futures
from datetime import datetime, timezone, timedelta
from typing import Dict, List, Tuple, Optional
import hashlib
import base64

# ═══════════════════════════════════════════════════════════════
# CONFIGURATION
# ═══════════════════════════════════════════════════════════════

API_BASE = "https://api.mysuperhero.xyz"
ADMIN_PHONE = "9542900900"

# Hyderabad service area coordinates (VALID)
HYD_LAT = 17.3850
HYD_LNG = 78.4867
HYD_LAT_2 = 17.4485
HYD_LNG_2 = 78.3908
HYD_LAT_3 = 17.4239
HYD_LNG_3 = 78.4738

# Outside service area coordinates (SHOULD BE REJECTED)
MUMBAI_LAT = 19.0760
MUMBAI_LNG = 72.8777
DELHI_LAT = 28.7041
DELHI_LNG = 77.1025
BANGALORE_LAT = 12.9716
BANGALORE_LNG = 77.5946

# Test result tracking
PASS = 0
FAIL = 0
WARN = 0
CRITICAL = 0
ISSUES = []
SECURITY_ISSUES = []

# Test execution timing
TEST_START_TIME = None
TEST_TOKENS = {}  # Store tokens for reuse

# ═══════════════════════════════════════════════════════════════
# HELPER FUNCTIONS
# ═══════════════════════════════════════════════════════════════

def p(label: str, ok: bool, detail: str = "", severity: str = "HIGH"):
    """Pass/Fail marker"""
    global PASS, FAIL, CRITICAL
    status = "✅ PASS" if ok else "❌ FAIL"
    print(f"  {status}: {label}", f"| {detail}" if detail else "")
    if ok:
        PASS += 1
    else:
        if severity == "CRITICAL":
            CRITICAL += 1
        FAIL += 1
        ISSUES.append({
            "severity": severity,
            "test": label,
            "detail": detail,
            "timestamp": datetime.now(timezone.utc).isoformat()
        })

def warn(label: str, detail: str = ""):
    """Warning marker"""
    global WARN
    print(f"  ⚠️  WARN: {label}", f"| {detail}" if detail else "")
    WARN += 1
    ISSUES.append({
        "severity": "MEDIUM",
        "test": label,
        "detail": detail,
        "timestamp": datetime.now(timezone.utc).isoformat()
    })

def security_issue(label: str, detail: str, severity: str = "CRITICAL"):
    """Security vulnerability marker"""
    global SECURITY_ISSUES
    SECURITY_ISSUES.append({
        "severity": severity,
        "vulnerability": label,
        "detail": detail,
        "timestamp": datetime.now(timezone.utc).isoformat()
    })
    p(label, False, detail, severity)

def section(title: str):
    """Print test section header"""
    print(f"\n{'='*80}")
    print(f"  {title}")
    print(f"{'='*80}")

def post(path: str, json_data: dict, headers: Optional[dict] = None, timeout: int = 15):
    """HTTP POST wrapper"""
    return requests.post(f"{API_BASE}{path}", json=json_data, headers=headers, timeout=timeout)

def get(path: str, headers: Optional[dict] = None, params: Optional[dict] = None, timeout: int = 15):
    """HTTP GET wrapper"""
    return requests.get(f"{API_BASE}{path}", headers=headers, params=params, timeout=timeout)

def put(path: str, json_data: dict, headers: Optional[dict] = None, timeout: int = 15):
    """HTTP PUT wrapper"""
    return requests.put(f"{API_BASE}{path}", json=json_data, headers=headers, timeout=timeout)

def delete(path: str, headers: Optional[dict] = None, timeout: int = 15):
    """HTTP DELETE wrapper"""
    return requests.delete(f"{API_BASE}{path}", headers=headers, timeout=timeout)

def patch(path: str, json_data: dict, headers: Optional[dict] = None, timeout: int = 15):
    """HTTP PATCH wrapper"""
    return requests.patch(f"{API_BASE}{path}", json=json_data, headers=headers, timeout=timeout)

# ═══════════════════════════════════════════════════════════════
# AUTHENTICATION HELPERS
# ═══════════════════════════════════════════════════════════════

def start_otp(phone: str, role: str = "BUYER") -> requests.Response:
    """Start OTP flow"""
    return post("/api/v1/auth/otp/start", {"phone": phone, "role": role})

def verify_otp(phone: str, otp: str, role: str = "BUYER") -> Optional[str]:
    """Verify OTP and return token"""
    resp = post("/api/v1/auth/otp/verify", {"phone": phone, "otp": otp, "role": role})
    if resp.status_code != 200:
        return None
    data = resp.json()
    return data.get("accessToken") or data.get("token")

def create_test_user(role: str = "BUYER") -> Tuple[Optional[str], str]:
    """Create a test user and return (token, phone)"""
    import random
    # Wait a bit to avoid rate limiting
    time.sleep(2)
    phone = "9" + ''.join([str(random.randint(0, 9)) for _ in range(9)])
    resp = start_otp(phone, role)
    
    if resp.status_code != 200:
        return None, phone
    
    otp_data = resp.json()
