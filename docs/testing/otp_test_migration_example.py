#!/usr/bin/env python3
"""
OTP Test Migration Example
==========================

This file demonstrates how to update test scripts that previously relied on
the `devOtp` field in API responses.

As of 2026-07-17, production environment no longer returns OTP codes in API
responses for security reasons (Task 1.1-1.4 completed).

This example shows:
1. OLD pattern (deprecated)
2. NEW pattern (production-compatible)
3. Environment-aware helper functions
"""

import os
import sys
import requests
from typing import Optional, Tuple

# Configuration
BASE_URL = os.environ.get("API_BASE_URL", "https://api.mysuperhero.xyz")
TEST_ENV = os.environ.get("TEST_ENV", "production")  # production, staging, development

# Test accounts
TEST_PHONES = {
    "buyer": "9000000101",
    "helper": "9000000102",
    "mediator": "9000000201",
    "admin": "9000000301"
}


# ============================================================================
# ❌ OLD PATTERN - DEPRECATED (Don't use this)
# ============================================================================

def login_buyer_OLD_PATTERN(phone: str) -> Optional[str]:
    """
    OLD PATTERN - This will fail in production!
    
    This code assumes devOtp is available in the response.
    In production, devOtp is always null.
    """
    print("⚠️  WARNING: Using deprecated OTP pattern")
    
    # Request OTP
    resp = requests.post(
        f"{BASE_URL}/api/v1/auth/otp/start",
        json={"phone": phone, "role": "BUYER"}
    )
    
    if resp.status_code != 200:
        print(f"❌ Failed to start OTP: {resp.status_code}")
        return None
    
    # ❌ THIS FAILS IN PRODUCTION - devOtp is null
    otp = resp.json().get("devOtp")
    
    if not otp:
        # This will always happen in production
        print("❌ FAIL: OTP not returned in response")
        print("   The devOtp field is null in production for security")
        return None
    
    # Verify OTP
    verify_resp = requests.post(
        f"{BASE_URL}/api/v1/auth/otp/verify",
        json={"phone": phone, "otp": otp}
    )
    
    if verify_resp.status_code == 200:
        return verify_resp.json().get("token")
    
    return None


# ============================================================================
# ✅ NEW PATTERN 1 - Interactive Testing (Recommended for Manual Tests)
# ============================================================================

def login_buyer_INTERACTIVE(phone: str, role: str = "BUYER") -> Optional[str]:
    """
    NEW PATTERN - Interactive OTP entry
    
    Prompts the tester to enter OTP from their SMS message.
    Best for manual testing and debugging.
    """
    print(f"\n{'='*60}")
    print(f"🔐 Login Test: {role}")
    print(f"{'='*60}")
    
    # Request OTP
    print(f"📤 Requesting OTP for {phone}...")
    resp = requests.post(
        f"{BASE_URL}/api/v1/auth/otp/start",
        json={"phone": phone, "role": role}
    )
    
    if resp.status_code != 200:
        print(f"❌ Failed to start OTP: {resp.status_code}")
        print(f"   Response: {resp.text}")
        return None
    
    response_data = resp.json()
    
    # Check if OTP was sent successfully
    if not response_data.get("sent"):
        print("❌ OTP sending failed")
        return None
    
    print(f"✅ OTP sent to {phone}")
    
    # Check if devOtp is available (staging/dev environment)
    dev_otp = response_data.get("devOtp")
    
    if dev_otp:
        # Development/staging environment - OTP in response
        print(f"🔧 Dev mode detected: OTP = {dev_otp}")
        otp = dev_otp
    else:
        # Production environment - prompt for OTP
        print(f"\n📱 Check SMS on {phone}")
        print(f"💡 Or check server logs: sudo journalctl -u superheroo-api -f | grep OTP")
        otp = input("\nEnter 6-digit OTP: ").strip()
    
    # Verify OTP
    print(f"\n🔍 Verifying OTP...")
    verify_resp = requests.post(
        f"{BASE_URL}/api/v1/auth/otp/verify",
        json={"phone": phone, "otp": otp}
    )
    
    if verify_resp.status_code == 200:
        token = verify_resp.json().get("token")
        print(f"✅ Login successful!")
        print(f"🎫 Token: {token[:20]}...")
        return token
    else:
        print(f"❌ OTP verification failed: {verify_resp.status_code}")
        print(f"   Response: {verify_resp.text}")
        return None


# ============================================================================
# ✅ NEW PATTERN 2 - Environment-Aware (Recommended for Automated Tests)
# ============================================================================

def get_otp_code(phone: str, role: str = "BUYER") -> Optional[str]:
    """
    Environment-aware OTP retrieval
    
    - In staging/dev: Returns OTP from response
    - In production: Returns None (caller must handle)
    """
    resp = requests.post(
        f"{BASE_URL}/api/v1/auth/otp/start",
        json={"phone": phone, "role": role}
    )
    
    if resp.status_code != 200:
        return None
    
    response_data = resp.json()
    
    if not response_data.get("sent"):
        return None
    
    # Try to get OTP from response (works in dev/staging)
    otp = response_data.get("devOtp")
    
    return otp  # Will be None in production


def login_buyer_AUTOMATED(phone: str, role: str = "BUYER", 
                         fallback_otp: Optional[str] = None) -> Optional[str]:
    """
    NEW PATTERN - Automated testing with environment detection
    
    - Tries to get OTP from response (dev/staging)
    - Falls back to provided OTP if response doesn't have it (production)
    - Returns None if no OTP available (test should skip or use manual method)
    """
    otp = get_otp_code(phone, role)
    
    if not otp:
        if TEST_ENV == "production":
            print(f"⚠️  Production environment: OTP not available in response")
            print(f"   Use interactive method or provide fallback OTP")
            
            if fallback_otp:
                print(f"   Using fallback OTP provided by caller")
                otp = fallback_otp
            else:
                print(f"   Skipping test - no OTP available")
                return None
        else:
            print(f"❌ Failed to get OTP even in {TEST_ENV} environment")
            return None
    
    # Verify OTP
    verify_resp = requests.post(
        f"{BASE_URL}/api/v1/auth/otp/verify",
        json={"phone": phone, "otp": otp}
    )
    
    if verify_resp.status_code == 200:
        return verify_resp.json().get("token")
    
    return None


# ============================================================================
# ✅ NEW PATTERN 3 - Pytest-Compatible (Skip in Production)
# ============================================================================

def pytest_login_buyer(phone: str, role: str = "BUYER") -> Tuple[bool, Optional[str]]:
    """
    Pytest-compatible login that returns success flag and token
    
    Usage with pytest:
    
        import pytest
        
        @pytest.mark.skipif(
            os.environ.get("TEST_ENV") == "production",
            reason="OTP not available in production responses"
        )
        def test_buyer_flow():
            success, token = pytest_login_buyer("9000000101")
            assert success, "Login failed"
            # Continue with test...
    """
    otp = get_otp_code(phone, role)
    
    if not otp:
        # Return failure flag - test should skip
        return False, None
    
    # Verify OTP
    verify_resp = requests.post(
        f"{BASE_URL}/api/v1/auth/otp/verify",
        json={"phone": phone, "otp": otp}
    )
    
    if verify_resp.status_code == 200:
        token = verify_resp.json().get("token")
        return True, token
    
    return False, None


# ============================================================================
# HELPER: Environment Detection
# ============================================================================

def detect_environment() -> str:
    """
    Detect test environment based on BASE_URL
    """
    if "localhost" in BASE_URL or "127.0.0.1" in BASE_URL:
        return "development"
    elif "staging" in BASE_URL:
        return "staging"
    elif "api.mysuperhero.xyz" in BASE_URL:
        return "production"
    else:
        return "unknown"


def is_otp_available_in_response() -> bool:
    """
    Quick check if OTP is available in API responses
    
    Returns True for dev/staging, False for production
    """
    test_phone = "9000000101"
    
    try:
        resp = requests.post(
            f"{BASE_URL}/api/v1/auth/otp/start",
            json={"phone": test_phone, "role": "BUYER"},
            timeout=5
        )
        
        if resp.status_code == 200:
            dev_otp = resp.json().get("devOtp")
            return dev_otp is not None
    except:
        pass
    
    return False


# ============================================================================
# EXAMPLE USAGE
# ============================================================================

def main():
    """
    Example usage of different OTP testing patterns
    """
    print(f"\n{'='*70}")
    print(f"OTP Test Migration Example")
    print(f"{'='*70}")
    print(f"Environment: {detect_environment()}")
    print(f"Base URL: {BASE_URL}")
    print(f"OTP in Response: {is_otp_available_in_response()}")
    print(f"{'='*70}\n")
    
    # Example 1: Interactive login (recommended for manual testing)
    print("\n" + "="*70)
    print("EXAMPLE 1: Interactive Login")
    print("="*70)
    print("Best for: Manual testing, debugging")
    print()
    
    buyer_phone = TEST_PHONES["buyer"]
    token = login_buyer_INTERACTIVE(buyer_phone, "BUYER")
    
    if token:
        print(f"\n✅ Successfully logged in as buyer")
        print(f"   Token can be used for subsequent API calls")
    else:
        print(f"\n❌ Login failed")
    
    # Example 2: Environment-aware login (recommended for CI/CD)
    print("\n" + "="*70)
    print("EXAMPLE 2: Environment-Aware Login")
    print("="*70)
    print("Best for: Automated tests that run in multiple environments")
    print()
    
    if is_otp_available_in_response():
        print("✅ OTP available in response - can run automated tests")
        token = login_buyer_AUTOMATED(buyer_phone, "BUYER")
        if token:
            print(f"✅ Automated login successful")
    else:
        print("⚠️  OTP not available in response (production mode)")
        print("   Automated tests should skip or use alternative approach")
    
    print("\n" + "="*70)
    print("Migration complete!")
    print("="*70)


if __name__ == "__main__":
    main()
