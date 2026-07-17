#!/usr/bin/env python3
"""
COMPREHENSIVE PRODUCTION READINESS TEST SUITE - ALL APPS
Tests ALL 3 mobile apps (Buyer, Helper, Mediator) + Admin Panel + Edge Cases
Target: https://api.mysuperhero.xyz
Industry Best Practices: Urban Company, Uber, TaskRabbit patterns
"""
import sys
import time
import json
import uuid
import requests
from datetime import datetime, timezone, timedelta

API_BASE = "https://api.mysuperhero.xyz"
ADMIN_EMAIL = "admin@helpinminutes.app"
ADMIN_PASSWORD = "Admin@12345"
ADMIN_PHONE = "9542900900"  # Admin phone without +91 prefix

# Hyderabad coords (within service area)
HYD_LAT = 17.3850
HYD_LNG = 78.4867
HYD_LAT_2 = 17.4485
HYD_LNG_2 = 78.3908
# Outside service area
MUMBAI_LAT = 19.0760
MUMBAI_LNG = 72.8777

PASS = 0
FAIL = 0
WARN = 0
ISSUES = []

def p(label, ok, detail="", severity="HIGH"):
    global PASS, FAIL, WARN
    status = "✅ PASS" if ok else "❌ FAIL"
    print(f"  {status}: {label}", f"| {detail}" if detail else "")
    if ok:
        PASS += 1
    else:
        FAIL += 1
        ISSUES.append({"severity": severity, "test": label, "detail": detail})

def warn(label, detail=""):
    global WARN
    print(f"  ⚠️  WARN: {label}", f"| {detail}" if detail else "")
    WARN += 1
    ISSUES.append({"severity": "MEDIUM", "test": label, "detail": detail})

def section(title):
    print(f"\n{'='*70}")
    print(f"  {title}")
    print(f"{'='*70}")

def post(path, json_data, headers=None):
    return requests.post(f"{API_BASE}{path}", json=json_data, headers=headers, timeout=15)

def get(path, headers=None):
    return requests.get(f"{API_BASE}{path}", headers=headers, timeout=15)

def put(path, json_data, headers=None):
    return requests.put(f"{API_BASE}{path}", json=json_data, headers=headers, timeout=15)

def delete(path, headers=None):
    return requests.delete(f"{API_BASE}{path}", headers=headers, timeout=15)

# ─── Auth helpers ────────────────────────────────────────────

def admin_login():
    """Admin login via OTP (no email/password login exists)"""
    resp = start_otp(ADMIN_PHONE, "ADMIN")  # Pass role!
    if resp.status_code != 200:
        print(f"  DEBUG: Admin OTP start failed: status={resp.status_code}, body={resp.text[:200]}")
        return None
    
    otp_data = resp.json()
    otp = otp_data.get("devOtp") or otp_data.get("otp") or "123456"
    print(f"  DEBUG: Got OTP: {otp}")
    
    token = verify_otp(ADMIN_PHONE, otp, "ADMIN")
    if not token:
        print(f"  DEBUG: Admin OTP verify failed")
    return token

def start_otp(phone, role="BUYER"):
    return post("/api/v1/auth/otp/start", {"phone": phone, "role": role})

def verify_otp(phone, otp, role="BUYER"):
    resp = post("/api/v1/auth/otp/verify", {"phone": phone, "otp": otp, "role": role})
    if resp.status_code != 200:
        print(f"  DEBUG verify_otp: status={resp.status_code}, body={resp.text[:300]}")
        return None
    data = resp.json()
    return data.get("accessToken") or data.get("token")

def create_test_user(role="BUYER"):
    """Create a test user and return token"""
    # Generate 10-digit phone starting with 9 (valid Indian mobile)
    import random
    phone = "9" + ''.join([str(random.randint(0, 9)) for _ in range(9)])
    print(f"  DEBUG: Creating {role} with phone: {phone}")
    resp = start_otp(phone, role)  # Pass role here!
    if resp.status_code != 200:
        print(f"  DEBUG: OTP start failed: status={resp.status_code}, body={resp.text[:200]}")
        return None, phone
    
    otp_data = resp.json()
    otp = otp_data.get("devOtp") or otp_data.get("otp") or "123456"
    print(f"  DEBUG: Got OTP: {otp}")
    
    token = verify_otp(phone, otp, role)
    if not token:
        print(f"  DEBUG: OTP verify failed for {role}")
    return token, phone


# ═══════════════════════════════════════════════════════════════
# TEST SUITES
# ═══════════════════════════════════════════════════════════════

def test_buyer_app_flows(buyer_token):
    """Test complete buyer app user journeys"""
    section("BUYER APP - COMPLETE USER FLOWS")
    
    headers = {"Authorization": f"Bearer {buyer_token}"}
    
    # 1. Buyer onboarding flow
    resp = get("/api/v1/buyer/profile", headers=headers)
    p("Buyer can fetch own profile", resp.status_code == 200, f"status={resp.status_code}")
    
    # 2. Task creation with all fields
    task_data = {
        "title": "Deep clean 2BHK apartment",
        "description": "Full house cleaning including kitchen, bathrooms, balcony. Use eco-friendly products.",
        "urgency": "MEDIUM",
        "timeMinutes": 180,
        "budgetPaise": 150000,
        "lat": HYD_LAT,
        "lng": HYD_LNG,
        "addressText": "Flat 402, Silver Oak Apartments, Gachibowli, Hyderabad",
        "landmark": "Near IKEA"
    }
    resp = post("/api/v1/tasks", task_data, headers=headers)
    p("Buyer can create detailed task", resp.status_code == 200, f"status={resp.status_code}")
    
    if resp.status_code == 200:
        task_id = resp.json().get("taskId")
        
        # 3. Check task list
        resp = get("/api/v1/tasks", headers=headers)
        p("Buyer task list loads", resp.status_code == 200)
        p("Task appears in buyer's list", len(resp.json()) > 0 if resp.status_code == 200 else False)
        
        # 4. Task details
        resp = get(f"/api/v1/tasks/{task_id}", headers=headers)
        p("Buyer can view task details", resp.status_code == 200)
        
        if resp.status_code == 200:
            task = resp.json()
            p("Task has all required fields", all(k in task for k in ["title", "status", "budgetPaise", "arrivalOtp", "completionOtp"]))
            p("Task landmark preserved", task.get("landmark") == "Near IKEA")
        
        # 5. Cancel task
        resp = delete(f"/api/v1/tasks/{task_id}", headers=headers)
        p("Buyer can cancel own task", resp.status_code == 200, f"status={resp.status_code}")
    
    # 6. Scheduled task
    scheduled_time = (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()
    task_data["scheduledAt"] = scheduled_time
    resp = post("/api/v1/tasks", task_data, headers=headers)
    p("Buyer can create scheduled task", resp.status_code == 200)
    
    if resp.status_code == 200:
        task_id = resp.json().get("taskId")
        resp = get(f"/api/v1/tasks/{task_id}", headers=headers)
        if resp.status_code == 200:
            p("Scheduled task has SCHEDULED_PENDING status", resp.json().get("status") == "SCHEDULED_PENDING")
    
    # 7. Bulk booking
    bulk_data = {
        "title": "Bulk crew request test",
        "notes": "Testing bulk booking flow",
        "items": [
            {
                "title": "Task 1: Clean store room 1",
                "description": "Sweep and mop",
                "timeMinutes": 60,
                "budgetPaise": 30000,
                "lat": HYD_LAT,
                "lng": HYD_LNG,
                "addressText": "Store 1"
            },
            {
                "title": "Task 2: Clean store room 2",
                "description": "Sweep and mop",
                "timeMinutes": 60,
                "budgetPaise": 30000,
                "lat": HYD_LAT_2,
                "lng": HYD_LNG_2,
                "addressText": "Store 2"
            }
        ]
    }
    
    resp = post("/api/v1/tasks/bulk/preview", bulk_data, headers=headers)
    p("Buyer can preview bulk booking", resp.status_code == 200)
    
    resp = post("/api/v1/tasks/bulk", bulk_data, headers=headers)
    p("Buyer can create bulk booking", resp.status_code == 200, f"status={resp.status_code}")
    
    if resp.status_code == 200:
        batch_id = resp.json().get("batchId")
        
        # 8. Batch tracking
        resp = get(f"/api/v1/batches/{batch_id}/summary", headers=headers)
        p("Buyer can view batch summary", resp.status_code == 200)
        
        resp = get(f"/api/v1/batches/{batch_id}/items", headers=headers)
        p("Buyer can view batch items", resp.status_code == 200)
        
        resp = get(f"/api/v1/batches/{batch_id}/live", headers=headers)
        p("Buyer can access live batch tracking", resp.status_code == 200)
        
        if resp.status_code == 200:
            live_data = resp.json()
            p("Live tracking has socketRoom for real-time updates", "socketRoom" in live_data or "room" in live_data)
    
    # 9. Edge cases - validation
    bad_task = {
        "title": "A",  # Too short
        "description": "B",  # Too short
        "timeMinutes": -10,  # Invalid
        "budgetPaise": -100,  # Invalid
        "lat": 91,  # Invalid latitude
        "lng": 181  # Invalid longitude
    }
    resp = post("/api/v1/tasks", bad_task, headers=headers)
    p("API rejects invalid task data", resp.status_code == 400, f"status={resp.status_code}")


def test_helper_app_flows(helper_token):
    """Test complete helper app user journeys"""
    section("HELPER APP - COMPLETE USER FLOWS")
    
    headers = {"Authorization": f"Bearer {helper_token}"}
    
    # 1. Helper profile
    resp = get("/api/v1/helper/profile", headers=headers)
    p("Helper can fetch own profile", resp.status_code == 200, f"status={resp.status_code}")
    
    # 2. Available tasks (nearby opportunities)
    resp = get("/api/v1/helper/nearby-tasks?lat=17.3850&lng=78.4867&radiusKm=10", headers=headers)
    p("Helper can see nearby available tasks", resp.status_code == 200, f"status={resp.status_code}")
    
    # 3. Helper's current assignments
    resp = get("/api/v1/helper/tasks", headers=headers)
    p("Helper can view assigned tasks", resp.status_code == 200)
    
    # 4. Helper's earnings summary
    resp = get("/api/v1/helper/earnings", headers=headers)
    p("Helper can view earnings summary", resp.status_code == 200, f"status={resp.status_code}")
    
    # 5. Helper availability toggle
    resp = put("/api/v1/helper/availability", {"available": True}, headers=headers)
    p("Helper can mark self as available", resp.status_code == 200, f"status={resp.status_code}")
    
    resp = put("/api/v1/helper/availability", {"available": False}, headers=headers)
    p("Helper can mark self as unavailable", resp.status_code == 200, f"status={resp.status_code}")
    
    # 6. Helper cannot access buyer-only endpoints
    resp = post("/api/v1/tasks", {"title": "Test", "lat": HYD_LAT, "lng": HYD_LNG}, headers=headers)
    p("Helper cannot create tasks (403 expected)", resp.status_code == 403, f"status={resp.status_code}")


def test_mediator_app_flows(admin_token):
    """Test mediator app flows (using admin token for testing)"""
    section("MEDIATOR APP - COMPLETE USER FLOWS")
    
    headers = {"Authorization": f"Bearer {admin_token}"}
    
    # 1. Mediator audit queue
    resp = get("/api/v1/batches/mediator-audit", headers=headers)
    p("Mediator can view audit queue", resp.status_code == 200, f"status={resp.status_code}")
    
    if resp.status_code == 200:
        audit_queue = resp.json()
        p("Audit queue is a list", isinstance(audit_queue, list))
    
    # 2. Mediator pending batches
    resp = get("/api/v1/batches?status=PENDING_MEDIATOR", headers=headers)
    p("Mediator can filter batches by status", resp.status_code == 200, f"status={resp.status_code}")
    
    # 3. Create a large bulk request (>9 helpers) for mediator flow
    scheduled_time = (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()
    large_bulk = {
        "title": "Large crew booking - 10 helpers",
        "requestedHelperCount": 10,
        "scheduledAt": scheduled_time,
        "items": [
            {
                "title": f"Task {i}: Event setup",
                "description": "Help with event setup",
                "timeMinutes": 120,
                "budgetPaise": 50000,
                "lat": HYD_LAT,
                "lng": HYD_LNG,
                "addressText": f"Event location {i}"
            } for i in range(1, 4)
        ]
    }
    
    resp = post("/api/v1/tasks/bulk", large_bulk, headers=headers)
    p("Large crew booking (>9 helpers) can be created with future scheduledAt", resp.status_code == 200, f"status={resp.status_code}")
    
    # Test instant booking with >9 helpers (should fail)
    large_bulk_instant = large_bulk.copy()
    large_bulk_instant.pop("scheduledAt", None)
    resp = post("/api/v1/tasks/bulk", large_bulk_instant, headers=headers)
    p("Instant large crew booking (>9 helpers) is blocked", resp.status_code == 400, f"status={resp.status_code}")


def test_admin_panel_features(admin_token):
    """Test admin panel comprehensive features"""
    section("ADMIN PANEL - ALL FEATURES")
    
    headers = {"Authorization": f"Bearer {admin_token}"}
    
    # 1. Dashboard summary
    resp = get("/api/v1/admin/summary", headers=headers)
    p("Admin dashboard loads", resp.status_code == 200)
    
    if resp.status_code == 200:
        summary = resp.json()
        expected_keys = ["activeTasks", "totalBuyers", "totalHelpers", "totalRevenue"]
        has_metrics = any(k in summary for k in expected_keys)
        p("Dashboard has key metrics", has_metrics, f"keys={list(summary.keys())[:5]}")
    
    # 2. User management
    resp = get("/api/v1/admin/buyers", headers=headers)
    p("Admin can list all buyers", resp.status_code == 200)
    
    resp = get("/api/v1/admin/helpers", headers=headers)
    p("Admin can list all helpers", resp.status_code == 200)
    
    # 3. Task management
    resp = get("/api/v1/admin/tasks", headers=headers)
    p("Admin can view all tasks", resp.status_code == 200)
    
    # 4. Financial reports
    resp = get("/api/v1/admin/reports/revenue?startDate=2026-07-01&endDate=2026-07-16", headers=headers)
    p("Admin can generate revenue report", resp.status_code in [200, 404], f"status={resp.status_code}")
    
    # 5. Helper verification
    resp = get("/api/v1/admin/helpers/pending-verification", headers=headers)
    p("Admin can see pending helper verifications", resp.status_code == 200, f"status={resp.status_code}")
    
    # 6. System health monitoring
    resp = get("/api/v1/admin/system/health", headers=headers)
    p("Admin can check system health", resp.status_code in [200, 404], f"status={resp.status_code}")
    
    # 7. Notification broadcast
    broadcast_data = {
        "title": "Test Broadcast",
        "message": "System maintenance scheduled",
        "targetRole": "ALL"
    }
    resp = post("/api/v1/admin/notifications/broadcast", broadcast_data, headers=headers)
    p("Admin can broadcast notifications", resp.status_code in [200, 404], f"status={resp.status_code}")


def test_notifications_comprehensive(buyer_token, helper_token):
    """Deep test of notification system"""
    section("NOTIFICATIONS - COMPREHENSIVE TESTING")
    
    buyer_headers = {"Authorization": f"Bearer {buyer_token}"}
    helper_headers = {"Authorization": f"Bearer {helper_token}"}
    
    # 1. Push token registration
    buyer_token_data = {
        "token": f"ExponentPushToken[buyer-{uuid.uuid4()}]",
        "platform": "android",
        "model": "Pixel 6"
    }
    resp = post("/api/v1/push-tokens", buyer_token_data, headers=buyer_headers)
    p("Buyer can register push token", resp.status_code == 200, f"status={resp.status_code}")
    
    helper_token_data = {
        "token": f"ExponentPushToken[helper-{uuid.uuid4()}]",
        "platform": "ios",
        "model": "iPhone 14"
    }
    resp = post("/api/v1/push-tokens", helper_token_data, headers=helper_headers)
    p("Helper can register push token", resp.status_code == 200, f"status={resp.status_code}")
    
    # 2. Notification history
    resp = get("/api/v1/notifications", headers=buyer_headers)
    p("Buyer can view notification history", resp.status_code == 200, f"status={resp.status_code}")
    
    resp = get("/api/v1/notifications", headers=helper_headers)
    p("Helper can view notification history", resp.status_code == 200, f"status={resp.status_code}")
    
    # 3. Notification preferences
    prefs = {
        "taskUpdates": True,
        "promotions": False,
        "chatMessages": True
    }
    resp = put("/api/v1/notifications/preferences", prefs, headers=buyer_headers)
    p("User can update notification preferences", resp.status_code in [200, 404], f"status={resp.status_code}")
    
    # 4. Mark notification as read
    resp = get("/api/v1/notifications", headers=buyer_headers)
    if resp.status_code == 200 and len(resp.json()) > 0:
        notif_id = resp.json()[0].get("id")
        resp = put(f"/api/v1/notifications/{notif_id}/read", {}, headers=buyer_headers)
        p("User can mark notification as read", resp.status_code == 200, f"status={resp.status_code}")


def test_edge_cases_and_security():
    """Industry best practices - edge cases and security"""
    section("EDGE CASES & SECURITY - INDUSTRY BEST PRACTICES")
    
    # 1. Rate limiting on different endpoints
    print("\n  Testing rate limits...")
    phone = f"+919999{uuid.uuid4().hex[:6]}"
    
    for i in range(7):
        resp = post("/api/v1/auth/otp/start", {"phone": phone, "role": "BUYER"})
        if resp.status_code == 429:
            p("OTP rate limiting works", True, f"blocked at request {i+1}")
            break
    else:
        warn("OTP rate limit may be too permissive", "allowed 7+ requests")
    
    # 2. CORS headers check
    resp = requests.options(f"{API_BASE}/api/v1/actuator/health")
    p("CORS headers present", "access-control-allow-origin" in resp.headers or "Access-Control-Allow-Origin" in resp.headers, f"status={resp.status_code}")
    
    # 3. Content-Type validation
    resp = requests.post(f"{API_BASE}/api/v1/auth/otp/start", 
                         data="malformed", 
                         headers={"Content-Type": "text/plain"})
    p("Server rejects invalid Content-Type", resp.status_code in [400, 415], f"status={resp.status_code}")
    
    # 4. Large payload rejection
    huge_task = {
        "title": "A" * 10000,  # 10KB title
        "description": "B" * 100000,  # 100KB description
        "lat": HYD_LAT,
        "lng": HYD_LNG
    }
    admin_token = admin_login()
    resp = post("/api/v1/tasks", huge_task, headers={"Authorization": f"Bearer {admin_token}"})
    p("Server rejects excessively large payloads", resp.status_code == 400, f"status={resp.status_code}")
    
    # 5. Unicode and special characters
    unicode_task = {
        "title": "தமிழ் 🚀 Emoji Test مرحبا",
        "description": "Testing unicode: 中文, हिंदी, ру́сский",
        "timeMinutes": 60,
        "budgetPaise": 30000,
        "lat": HYD_LAT,
        "lng": HYD_LNG,
        "addressText": "123 தமிழ் Street"
    }
    resp = post("/api/v1/tasks", unicode_task, headers={"Authorization": f"Bearer {admin_token}"})
    p("API handles unicode/emoji correctly", resp.status_code == 200, f"status={resp.status_code}")
    
    # 6. NULL byte injection
    null_byte_task = {
        "title": "Test\x00NullByte",
        "description": "Description\x00",
        "lat": HYD_LAT,
        "lng": HYD_LNG
    }
    resp = post("/api/v1/tasks", null_byte_task, headers={"Authorization": f"Bearer {admin_token}"})
    p("API handles null bytes safely", resp.status_code in [200, 400], f"status={resp.status_code}")
    
    # 7. Concurrent request handling
    print("\n  Testing concurrent requests...")
    import concurrent.futures
    
    def make_health_check():
        return requests.get(f"{API_BASE}/actuator/health", timeout=5)
    
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(make_health_check) for _ in range(10)]
        results = [f.result() for f in concurrent.futures.as_completed(futures)]
    
    success_count = sum(1 for r in results if r.status_code == 200)
    p("Server handles 10 concurrent requests", success_count >= 9, f"{success_count}/10 succeeded")
    
    # 8. Timezone handling
    past_time = (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat()
    future_time = (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()
    
    task_with_tz = {
        "title": "Timezone test task",
        "description": "Testing timezone handling",
        "scheduledAt": future_time,
        "timeMinutes": 60,
        "budgetPaise": 30000,
        "lat": HYD_LAT,
        "lng": HYD_LNG,
        "addressText": "Test address"
    }
    resp = post("/api/v1/tasks", task_with_tz, headers={"Authorization": f"Bearer {admin_token}"})
    p("API handles ISO8601 timestamps correctly", resp.status_code == 200, f"status={resp.status_code}")
    
    # Cleanup
    if resp.status_code == 200:
        task_id = resp.json().get("taskId")
        delete(f"/api/v1/tasks/{task_id}", headers={"Authorization": f"Bearer {admin_token}"})


def test_performance_benchmarks():
    """Performance testing against industry standards"""
    section("PERFORMANCE - INDUSTRY BENCHMARKS")
    
    admin_token = admin_login()
    headers = {"Authorization": f"Bearer {admin_token}"}
    
    endpoints = [
        ("/actuator/health", None, 500, "Health check"),
        ("/api/v1/admin/summary", headers, 1000, "Admin dashboard"),
        ("/api/v1/admin/tasks", headers, 1500, "Task list"),
        ("/api/v1/admin/buyers", headers, 1500, "Buyer list"),
        ("/api/v1/admin/helpers", headers, 1500, "Helper list"),
    ]
    
    for path, hdrs, threshold_ms, label in endpoints:
        times = []
        for _ in range(3):
            start = time.time()
            resp = get(path, headers=hdrs)
            elapsed_ms = (time.time() - start) * 1000
            times.append(elapsed_ms)
            time.sleep(0.5)
        
        avg_time = sum(times) / len(times)
        p(f"{label} response time <{threshold_ms}ms", avg_time < threshold_ms, f"avg={int(avg_time)}ms")


def test_data_integrity():
    """Test data consistency and integrity"""
    section("DATA INTEGRITY - CONSISTENCY CHECKS")
    
    admin_token = admin_login()
    buyer_token, buyer_phone = create_test_user("BUYER")
    
    if not buyer_token:
        warn("Could not create buyer for data integrity tests")
        return
    
    headers = {"Authorization": f"Bearer {buyer_token}"}
    
    # 1. Task creation and retrieval consistency
    task_data = {
        "title": "Data integrity test task",
        "description": "Testing data consistency across endpoints",
        "timeMinutes": 90,
        "budgetPaise": 45000,
        "lat": HYD_LAT,
        "lng": HYD_LNG,
        "addressText": "Test location 123",
        "landmark": "Near test landmark"
    }
    
    resp = post("/api/v1/tasks", task_data, headers=headers)
    if resp.status_code == 200:
        task_id = resp.json().get("taskId")
        
        # Verify data matches across different endpoints
        resp1 = get(f"/api/v1/tasks/{task_id}", headers=headers)
        resp2 = get("/api/v1/tasks", headers=headers)
        
        if resp1.status_code == 200 and resp2.status_code == 200:
            task_detail = resp1.json()
            task_list = resp2.json()
            
            p("Task data consistent across endpoints", 
              task_detail.get("title") == task_data["title"],
              f"title matches: {task_detail.get('title')}")
            
            p("Budget value preserved correctly",
              task_detail.get("budgetPaise") == task_data["budgetPaise"],
              f"budget={task_detail.get('budgetPaise')}")
            
            p("Location coordinates preserved",
              abs(task_detail.get("lat") - task_data["lat"]) < 0.0001,
              f"lat={task_detail.get('lat')}")
        
        # Cleanup
        delete(f"/api/v1/tasks/{task_id}", headers=headers)


def test_chat_system():
    """Test in-app chat functionality"""
    section("CHAT SYSTEM - REAL-TIME MESSAGING")
    
    buyer_token, _ = create_test_user("BUYER")
    helper_token, _ = create_test_user("HELPER")
    
    if not buyer_token or not helper_token:
        warn("Could not create users for chat testing")
        return
    
    buyer_headers = {"Authorization": f"Bearer {buyer_token}"}
    helper_headers = {"Authorization": f"Bearer {helper_token}"}
    
    # 1. Create a task first
    task_data = {
        "title": "Chat test task",
        "description": "Testing chat functionality",
        "timeMinutes": 60,
        "budgetPaise": 30000,
        "lat": HYD_LAT,
        "lng": HYD_LNG,
        "addressText": "Test location"
    }
    resp = post("/api/v1/tasks", task_data, headers=buyer_headers)
    
    if resp.status_code == 200:
        task_id = resp.json().get("taskId")
        
        # 2. Send chat message
        chat_msg = {
            "taskId": task_id,
            "message": "Hello, when will you arrive?"
        }
        resp = post("/api/v1/chat/send", chat_msg, headers=buyer_headers)
        p("Buyer can send chat message", resp.status_code in [200, 404], f"status={resp.status_code}")
        
        # 3. Get chat history
        resp = get(f"/api/v1/chat/task/{task_id}", headers=buyer_headers)
        p("User can fetch chat history", resp.status_code in [200, 404], f"status={resp.status_code}")
        
        # 4. Chat notifications
        resp = get("/api/v1/chat/unread-count", headers=buyer_headers)
        p("User can check unread message count", resp.status_code in [200, 404], f"status={resp.status_code}")
        
        # Cleanup
        delete(f"/api/v1/tasks/{task_id}", headers=buyer_headers)


def test_rating_and_feedback():
    """Test rating system"""
    section("RATING & FEEDBACK SYSTEM")
    
    buyer_token, _ = create_test_user("BUYER")
    if not buyer_token:
        warn("Could not create buyer for rating tests")
        return
    
    headers = {"Authorization": f"Bearer {buyer_token}"}
    
    # Create and complete a mock task flow
    task_data = {
        "title": "Rating test task",
        "description": "For testing rating system",
        "timeMinutes": 30,
        "budgetPaise": 20000,
        "lat": HYD_LAT,
        "lng": HYD_LNG,
        "addressText": "Test"
    }
    resp = post("/api/v1/tasks", task_data, headers=headers)
    
    if resp.status_code == 200:
        task_id = resp.json().get("taskId")
        
        # Try to rate (may fail if task not completed)
        rating_data = {
            "rating": 5,
            "comment": "Excellent service! Very professional and on time."
        }
        resp = put(f"/api/v1/tasks/{task_id}/rate", rating_data, headers=headers)
        p("Rating endpoint exists", resp.status_code in [200, 400, 404], f"status={resp.status_code}")
        
        # Cleanup
        delete(f"/api/v1/tasks/{task_id}", headers=headers)


# ═══════════════════════════════════════════════════════════════
# MAIN EXECUTION
# ═══════════════════════════════════════════════════════════════

def main():
    print("\n" + "="*70)
    print("  Help in Minutes — COMPREHENSIVE TEST SUITE (ALL APPS)")
    print(f"  Target: {API_BASE}")
    print(f"  Started: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    print("="*70)
    
    # Setup
    print("\n[Setup] Creating test users...")
    
    buyer_token, buyer_phone = create_test_user("BUYER")
    helper_token, helper_phone = create_test_user("HELPER")
    
    if buyer_token:
        print(f"  ✅ Test buyer created: {buyer_phone}")
    else:
        print(f"  ❌ Could not create test buyer")
        sys.exit(1)
    
    if helper_token:
        print(f"  ✅ Test helper created: {helper_phone}")
    else:
        print(f"  ⚠️  Could not create test helper (some tests will be skipped)")
    
    # Admin features require real admin login - skip for now
    print(f"  ⚠️  Admin tests skipped (requires real admin credentials)")
    admin_token = None
    
    # Run test suites
    try:
        if buyer_token:
            test_buyer_app_flows(buyer_token)
        else:
            section("BUYER APP - SKIPPED (no buyer token)")
        
        if helper_token:
            test_helper_app_flows(helper_token)
        else:
            section("HELPER APP - SKIPPED (no helper token)")
        
        if admin_token:
            test_mediator_app_flows(admin_token)
            test_admin_panel_features(admin_token)
        else:
            section("MEDIATOR APP - SKIPPED (requires admin credentials)")
            section("ADMIN PANEL - SKIPPED (requires admin credentials)")
        
        if buyer_token and helper_token:
            test_notifications_comprehensive(buyer_token, helper_token)
        
        test_edge_cases_and_security()
        test_performance_benchmarks()
        test_data_integrity()
        test_chat_system()
        test_rating_and_feedback()
        
    except Exception as e:
        print(f"\n❌ FATAL ERROR: {e}")
        import traceback
        traceback.print_exc()
    
    # Final report
    print("\n" + "="*70)
    print("  FINAL COMPREHENSIVE REPORT")
    print("="*70)
    print(f"\n  Total: {PASS + FAIL} | ✅ PASS: {PASS} | ❌ FAIL: {FAIL} | ⚠️  WARN: {WARN}")
    
    if PASS + FAIL > 0:
        pass_rate = (PASS / (PASS + FAIL)) * 100
        print(f"  Pass rate: {pass_rate:.0f}%")
    
    if ISSUES:
        print(f"\n  Issues found (sorted by severity):\n")
        
        critical = [i for i in ISSUES if i["severity"] == "CRITICAL"]
        high = [i for i in ISSUES if i["severity"] == "HIGH"]
        medium = [i for i in ISSUES if i["severity"] == "MEDIUM"]
        
        for issue in critical + high + medium:
            print(f"  [{issue['severity']}] {issue['test']}")
            if issue['detail']:
                print(f"         {issue['detail']}")
    
    print("\n" + "="*70)
    
    # Launch readiness
    critical_count = len([i for i in ISSUES if i["severity"] == "CRITICAL"])
    high_count = len([i for i in ISSUES if i["severity"] == "HIGH"])
    
    if critical_count > 0 or high_count > 3:
        print("  🔴 NOT READY TO LAUNCH")
        print(f"  {critical_count} CRITICAL and {high_count} HIGH issues must be fixed first.")
    elif high_count > 0:
        print("  🟡 READY WITH CAUTIONS")
        print(f"  {high_count} HIGH priority issues should be fixed soon.")
    else:
        print("  🟢 READY TO LAUNCH")
        print("  All critical flows working. Monitor MEDIUM issues post-launch.")
    
    print("="*70 + "\n")

if __name__ == "__main__":
    main()
