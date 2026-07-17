#!/usr/bin/env python3
"""
===================================================================================
SUPERHEROOO VAPT SECURITY VERIFICATION AUDIT
===================================================================================
This suite verifies the security posture of the backend APIs against:
1. Unauthenticated Endpoint Protections (CWE-306)
2. Broken Object Level Authorization / IDOR (CWE-285 / OWASP API1:2019)
3. Broken Function Level Authorization / Privilege Escalation (CWE-285 / OWASP API5:2019)
4. Input Sanitization & SQLi/XSS Protection (CWE-20 / CWE-79 / CWE-89)
5. Rate Limiting verification (CWE-307)
6. Administrative Role Registration Protection (CWE-269)

Target: http://localhost:8081
===================================================================================
"""

import sys
import time
import uuid
import requests

API_BASE = "http://localhost:8081"
HYD_LAT = 17.3850
HYD_LNG = 78.4867

# Test tracking
PASS = 0
FAIL = 0
VULNERABILITIES = []

def report_status(test_name: str, passed: bool, detail: str = "", vulnerability: str = ""):
    global PASS, FAIL
    status = "✅ PASS" if passed else "❌ FAIL"
    print(f"  {status}: {test_name}", f"| {detail}" if detail else "")
    if passed:
        PASS += 1
    else:
        FAIL += 1
        if vulnerability:
            VULNERABILITIES.append({
                "test": test_name,
                "vulnerability": vulnerability,
                "detail": detail
            })

def section(title: str):
    print(f"\n{'='*80}\n  {title}\n{'='*80}")

def authenticate_user(phone: str, role: str = "BUYER") -> str:
    """Helper to authenticate a user via Dev OTP verification"""
    start_payload = {"phone": phone, "role": role}
    r = requests.post(f"{API_BASE}/api/v1/auth/otp/start", json=start_payload)
    if r.status_code != 200:
        raise Exception(f"Start OTP failed for phone {phone} status={r.status_code}")
    
    otp = r.json().get("devOtp")
    if not otp:
        raise Exception("Dev OTP was not returned. Make sure the server runs in dev mode.")
        
    verify_payload = {"phone": phone, "otp": otp, "role": role}
    r = requests.post(f"{API_BASE}/api/v1/auth/otp/verify", json=verify_payload)
    if r.status_code != 200:
        raise Exception(f"Verify OTP failed status={r.status_code}")
        
    return r.json().get("accessToken")

def main():
    print("=== Superherooo VAPT Security Audit Suite ===")
    
    try:
        # Check if local server is online
        requests.get(f"{API_BASE}/health", timeout=3)
    except Exception:
        print(f"ERROR: Local Spring Boot API server is not running on {API_BASE}. Please start the server first.")
        sys.exit(1)

    # 1. AUTHENTICATION & ACCESS CONTROL
    section("1. UNAUTHENTICATED ENDPOINT PROTECTIONS")
    
    routes = [
        ("GET", "/api/v1/tasks"),
        ("POST", "/api/v1/tasks"),
        ("GET", "/api/v1/admin/summary"),
        ("GET", "/api/v1/mediator/jobs"),
        ("GET", "/api/v1/me"),
    ]
    
    for method, path in routes:
        if method == "GET":
            r = requests.get(f"{API_BASE}{path}")
        else:
            r = requests.post(f"{API_BASE}{path}", json={})
            
        report_status(
            f"Unauthenticated request blocks {method} {path}",
            r.status_code in [401, 403],
            f"status={r.status_code}",
            vulnerability="CWE-306 Missing Authentication for Sensitive Function" if r.status_code not in [401, 403] else ""
        )

    # Register two distinct test buyers for IDOR testing
    buyer_a_phone = "9111111111"
    buyer_b_phone = "9222222222"
    
    try:
        token_a = authenticate_user(buyer_a_phone, "BUYER")
        token_b = authenticate_user(buyer_b_phone, "BUYER")
        print("\n  [Info] Successfully authenticated Test Buyer A and Test Buyer B.")
    except Exception as e:
        print(f"Aborting tests: failed to log in test users. Detail: {e}")
        sys.exit(1)

    # Buyer A creates a task
    create_payload = {
        "title": "VAPT Audit Task",
        "description": "Task created for security audit testing",
        "urgency": "NORMAL",
        "timeMinutes": 30,
        "budgetPaise": 5000,
        "lat": HYD_LAT,
        "lng": HYD_LNG,
        "addressText": "Jubilee Hills"
    }
    
    r = requests.post(
        f"{API_BASE}/api/v1/tasks",
        json=create_payload,
        headers={"Authorization": f"Bearer {token_a}"}
    )
    if r.status_code != 200:
        print("Failed to create test task for Buyer A.")
        sys.exit(1)
        
    task_id = r.json().get("taskId")
    print(f"  [Info] Test task created successfully. ID: {task_id}")

    # 2. BROKEN OBJECT LEVEL AUTHORIZATION (BOLA / IDOR)
    section("2. BROKEN OBJECT LEVEL AUTHORIZATION (BOLA / IDOR)")
    
    # Buyer B attempts to fetch details of Buyer A's task
    r = requests.get(
        f"{API_BASE}/api/v1/tasks/{task_id}",
        headers={"Authorization": f"Bearer {token_b}"}
    )
    report_status(
        "BOLA: Buyer B blocked from viewing Buyer A's task details",
        r.status_code == 403,
        f"status={r.status_code}",
        vulnerability="CWE-285 / OWASP API1:2019 BOLA - Read Task Details" if r.status_code != 403 else ""
    )

    # Buyer B attempts to cancel Buyer A's task
    r = requests.post(
        f"{API_BASE}/api/v1/tasks/{task_id}/cancel",
        json={"reason": "Attacking cancellation endpoint"},
        headers={"Authorization": f"Bearer {token_b}"}
    )
    report_status(
        "BOLA: Buyer B blocked from cancelling Buyer A's task",
        r.status_code == 403,
        f"status={r.status_code}",
        vulnerability="CWE-285 / OWASP API1:2019 BOLA - Cancel Task" if r.status_code != 403 else ""
    )

    # Buyer B attempts to read chat messages of Buyer A's task
    r = requests.get(
        f"{API_BASE}/api/v1/tasks/{task_id}/chat/messages",
        headers={"Authorization": f"Bearer {token_b}"}
    )
    report_status(
        "BOLA: Buyer B blocked from reading Buyer A's task chat",
        r.status_code == 403,
        f"status={r.status_code}",
        vulnerability="CWE-285 / OWASP API1:2019 BOLA - Read Chat Messages" if r.status_code != 403 else ""
    )

    # Buyer B attempts to send chat message to Buyer A's task
    r = requests.post(
        f"{API_BASE}/api/v1/tasks/{task_id}/chat/messages",
        json={"message": "Attack payload message"},
        headers={"Authorization": f"Bearer {token_b}"}
    )
    report_status(
        "BOLA: Buyer B blocked from posting message to Buyer A's task chat",
        r.status_code == 403,
        f"status={r.status_code}",
        vulnerability="CWE-285 / OWASP API1:2019 BOLA - Write Chat Message" if r.status_code != 403 else ""
    )

    # 3. BROKEN FUNCTION LEVEL AUTHORIZATION (BFLA)
    section("3. BROKEN FUNCTION LEVEL AUTHORIZATION (BFLA)")
    
    # Buyer A attempts to view admin summary
    r = requests.get(
        f"{API_BASE}/api/v1/admin/summary",
        headers={"Authorization": f"Bearer {token_a}"}
    )
    report_status(
        "BFLA: Buyer blocked from Admin dashboard summary",
        r.status_code == 403,
        f"status={r.status_code}",
        vulnerability="CWE-285 / OWASP API5:2019 BFLA - Access Admin Summary" if r.status_code != 403 else ""
    )
    
    # Buyer A attempts to access mediator endpoints
    r = requests.get(
        f"{API_BASE}/api/v1/mediator/jobs",
        headers={"Authorization": f"Bearer {token_a}"}
    )
    report_status(
        "BFLA: Buyer blocked from Mediator jobs list",
        r.status_code == 403,
        f"status={r.status_code}",
        vulnerability="CWE-285 / OWASP API5:2019 BFLA - Access Mediator Endpoints" if r.status_code != 403 else ""
    )

    # 4. PRIVILEGE ESCALATION DURING REGISTRATION
    section("4. ADMINISTRATIVE ROLE REGISTRATION PROTECTION")
    
    reg_payloads = [
        {"email": "fakeadmin@helpinminutes.app", "password": "Password@123", "phone": "9988776655", "displayName": "Attacker", "role": "ADMIN"},
        {"email": "fakemediator@helpinminutes.app", "password": "Password@123", "phone": "9988776656", "displayName": "Attacker", "role": "MEDIATOR"},
        {"email": "fakesupport@helpinminutes.app", "password": "Password@123", "phone": "9988776657", "displayName": "Attacker", "role": "SUPPORT"}
    ]
    
    for pld in reg_payloads:
        r = requests.post(f"{API_BASE}/api/v1/auth/password/signup", json=pld)
        report_status(
            f"Signup endpoint blocks creation of {pld['role']} role",
            r.status_code == 400,
            f"status={r.status_code}",
            vulnerability=f"CWE-269 Privilege Escalation - Creating {pld['role']}" if r.status_code != 400 else ""
        )

    # 5. INPUT SANITIZATION (SQLi & XSS)
    section("5. INPUT SANITIZATION & SAFE DATABASE QUERIES")
    
    # SQL injection attempt inside strings (parameterized checks)
    sqli_task = create_payload.copy()
    sqli_task["title"] = "Title' OR '1'='1' --"
    sqli_task["description"] = "Description' OR '1'='1' --"
    r = requests.post(
        f"{API_BASE}/api/v1/tasks",
        json=sqli_task,
        headers={"Authorization": f"Bearer {token_a}"}
    )
    # Status code is expected to be 200 (created safely as a string parameter, no DB syntax crash)
    report_status(
        "SQLi: Single quotes / comment tokens in strings handled safely by JPA parameterized queries",
        r.status_code == 200,
        f"status={r.status_code}"
    )
    if r.status_code == 200:
        requests.post(f"{API_BASE}/api/v1/tasks/{r.json().get('taskId')}/cancel", json={"reason": "Clean up"}, headers={"Authorization": f"Bearer {token_a}"})

    # XSS script injection test
    xss_task = create_payload.copy()
    xss_task["title"] = "<script>alert('XSS')</script>"
    xss_task["description"] = "Hello <img src=x onerror=alert(1)>"
    r = requests.post(
        f"{API_BASE}/api/v1/tasks",
        json=xss_task,
        headers={"Authorization": f"Bearer {token_a}"}
    )
    report_status(
        "XSS: Script payloads in fields stored safely without breaking Jackson JSON encoding",
        r.status_code == 200,
        f"status={r.status_code}"
    )
    if r.status_code == 200:
        requests.post(f"{API_BASE}/api/v1/tasks/{r.json().get('taskId')}/cancel", json={"reason": "Clean up"}, headers={"Authorization": f"Bearer {token_a}"})

    # 6. RATE LIMITING HARDENING
    section("6. RATE LIMITING AUDIT")
    
    rate_limit_triggered = False
    rate_limit_ip_blocked_msg = ""
    
    # Send rapid OTP requests to hit rate limits (limit is 5 requests per minute for starting OTP)
    rate_limit_phone = "9555555555"
    print("  [Info] Sending 8 rapid OTP start requests to verify rate limits...")
    for i in range(8):
        resp = requests.post(f"{API_BASE}/api/v1/auth/otp/start", json={"phone": rate_limit_phone, "role": "BUYER"})
        if resp.status_code == 429:
            rate_limit_triggered = True
            rate_limit_ip_blocked_msg = resp.text
            break
            
    report_status(
        "Rate Limiter: Triggered 429 Too Many Requests on authentication route",
        rate_limit_triggered,
        f"triggered={rate_limit_triggered} message={rate_limit_ip_blocked_msg}",
        vulnerability="CWE-307 Rate Limiting Deficiencies" if not rate_limit_triggered else ""
    )

    # Cleanup Task A
    requests.post(
        f"{API_BASE}/api/v1/tasks/{task_id}/cancel",
        json={"reason": "Audit cleanup completed"},
        headers={"Authorization": f"Bearer {token_a}"}
    )

    # FINAL COMPLIANCE SUMMARY
    print(f"\n{'='*80}\n  FINAL VAPT AUDIT SUMMARY\n{'='*80}")
    print(f"  Tests Passed: {PASS}")
    print(f"  Tests Failed: {FAIL}")
    
    if len(VULNERABILITIES) > 0:
        print("\n  ⚠️  WARNING: VULNERABILITIES DETECTED!")
        for vuln in VULNERABILITIES:
            print(f"    - {vuln['test']}: {vuln['vulnerability']}")
            print(f"      Detail: {vuln['detail']}")
        sys.exit(1)
    else:
        print("\n  🏆 CONGRATULATIONS! ALL API SECURITY CHECKS PASSED. COMPLIANT WITH VAPT STANDARDS.")
        sys.exit(0)

if __name__ == "__main__":
    main()
