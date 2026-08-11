#!/usr/bin/env python3
"""
End-to-end API test against a locally running Superherooo backend.

Exercises the real HTTP surface the mobile apps call, covering the flows
changed in this launch-readiness pass plus regression guards for every
authentication bypass that was removed.
"""

import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8099"
HYD = (17.4401, 78.3489)          # Madhapur — inside the service area
MUMBAI = (19.0760, 72.8777)       # In India, outside Hyderabad

PASSED, FAILED = [], []


def call(method, path, body=None, token=None, expect=None):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            if not raw:
                return resp.status, None
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                # /api/v1/health replies text/plain.
                return resp.status, {"raw": raw}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return e.code, {"raw": raw}
    except Exception as e:  # noqa: BLE001
        return 0, {"error": str(e)}


def upload_selfie(task_id, stage, token):
    """Exercise the same multipart upload used by the partner mobile app."""
    boundary = f"----superherooo-e2e-{uuid.uuid4().hex}"
    fields = {
        "stage": stage,
        "lat": str(HYD[0]),
        "lng": str(HYD[1]),
        "addressText": "Madhapur, Hyderabad",
    }
    parts = []
    for name, value in fields.items():
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
        )
    # The storage boundary validates type and size; dev storage returns a local
    # placeholder if MinIO is absent, so no external service is required.
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"selfie\"; filename=\"e2e-selfie.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".encode()
        + b"\xff\xd8\xff\xdbsuperherooo-e2e\xff\xd9\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    req = urllib.request.Request(
        f"{BASE}/api/v1/tasks/{task_id}/selfie",
        data=b"".join(parts),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, json.loads(raw) if raw else None


def check(name, condition, detail=""):
    if condition:
        PASSED.append(name)
        print(f"  PASS  {name}")
    else:
        FAILED.append((name, detail))
        print(f"  FAIL  {name} :: {detail}")


def section(title):
    print(f"\n=== {title} ===")


# ─────────────────────────────────────────────────────────────────────────────
section("1. Health & public surface")

status, body = call("GET", "/api/v1/health")
check("health endpoint responds", status == 200, f"status={status}")

status, _ = call("GET", "/actuator/health")
check("actuator health reachable", status in (200, 503), f"status={status}")

# ─────────────────────────────────────────────────────────────────────────────
section("2. Authentication bypass regressions (must all be blocked)")

for phone in ("9999999991", "9999999992", "9999999993"):
    status, body = call("POST", "/api/v1/auth/otp/verify",
                        {"phone": phone, "otp": "123456", "role": "BUYER"})
    check(f"reviewer phone {phone} cannot bypass OTP", status >= 400,
          f"status={status} body={body}")

status, body = call("POST", "/api/v1/auth/otp/start", {"phone": "9876543210", "role": "BUYER"})
issued = (body or {}).get("devOtp")
status2, body2 = call("POST", "/api/v1/auth/otp/verify",
                      {"phone": "9876543210", "otp": "123456", "role": "BUYER"})
check("static OTP 123456 rejected", status2 >= 400 or issued == "123456",
      f"status={status2} body={body2}")

status, body = call("POST", "/api/v1/auth/password/login",
                    {"email": "admin@helpinminutes.app", "password": "Admin@12345"})
check("seeded admin password revoked (V54)", status >= 400, f"status={status} body={body}")

status, body = call("POST", "/api/v1/auth/password/login",
                    {"email": "buyer1@helpinminutes.app", "password": "Buyer@12345"})
check("seeded buyer password revoked (V54)", status >= 400, f"status={status}")

status, body = call("POST", "/api/v1/auth/password/signup", {
    "email": f"mediator-{uuid.uuid4().hex[:8]}@gmail.com", "password": "Hyd2026Secure",
    "phone": "9812345678", "displayName": "Sneaky", "role": "MEDIATOR"})
check("cannot self-signup as MEDIATOR", status >= 400, f"status={status}")

# ─────────────────────────────────────────────────────────────────────────────
section("3. Signup, validation and email verification")

cit_email = f"citizen-{uuid.uuid4().hex[:8]}@gmail.com"
cit_phone = f"9{uuid.uuid4().int % 900000000 + 100000000}"

status, body = call("POST", "/api/v1/auth/password/signup", {
    "email": cit_email, "password": "short1", "phone": cit_phone,
    "displayName": "Test Citizen", "role": "BUYER"})
check("weak password rejected", status == 400, f"status={status} body={body}")

status, body = call("POST", "/api/v1/auth/password/signup", {
    "email": cit_email, "password": "password123", "phone": cit_phone,
    "displayName": "Test Citizen", "role": "BUYER"})
check("common password rejected", status == 400, f"status={status}")

status, body = call("POST", "/api/v1/auth/password/signup", {
    "email": cit_email, "password": "Hyd2026Secure",
    "displayName": "Test Citizen", "role": "BUYER"})
check("signup without phone rejected", status == 400, f"status={status}")

status, body = call("POST", "/api/v1/auth/password/signup", {
    "email": cit_email, "password": "Hyd2026Secure", "phone": cit_phone,
    "displayName": "Test Citizen", "role": "BUYER"})
check("valid signup succeeds", status == 200 and body and body.get("accessToken"),
      f"status={status} body={body}")
citizen_token = (body or {}).get("accessToken")
citizen_refresh = (body or {}).get("refreshToken")

status, body = call("POST", "/api/v1/auth/password/signup", {
    "email": cit_email, "password": "Hyd2026Secure", "phone": "9700000001",
    "displayName": "Dup", "role": "BUYER"})
check("duplicate email rejected", status == 400, f"status={status}")

# ─────────────────────────────────────────────────────────────────────────────
section("4. Forgot / reset password")

status, body = call("POST", "/api/v1/auth/password/forgot", {"email": cit_email})
check("forgot password accepted", status == 200, f"status={status}")
reset_otp = (body or {}).get("devOtp")

status, body = call("POST", "/api/v1/auth/password/forgot",
                    {"email": "does-not-exist-anywhere@gmail.com"})
check("forgot password does not leak account existence",
      status == 200 and (body or {}).get("sent") is True, f"status={status} body={body}")

status, body = call("POST", "/api/v1/auth/password/reset",
                    {"email": cit_email, "otp": "000000", "newPassword": "Hyd2026Changed"})
check("reset with wrong code rejected", status >= 400, f"status={status}")

if reset_otp:
    status, body = call("POST", "/api/v1/auth/password/reset",
                        {"email": cit_email, "otp": reset_otp, "newPassword": "Hyd2026Changed"})
    check("reset with valid code succeeds", status == 200 and (body or {}).get("accessToken"),
          f"status={status} body={body}")
    citizen_token = (body or {}).get("accessToken") or citizen_token
    citizen_refresh = (body or {}).get("refreshToken") or citizen_refresh

    status, _ = call("POST", "/api/v1/auth/password/login",
                     {"email": cit_email, "password": "Hyd2026Changed"})
    check("can log in with the new password", status == 200, f"status={status}")

    status, _ = call("POST", "/api/v1/auth/password/login",
                     {"email": cit_email, "password": "Hyd2026Secure"})
    check("old password no longer works", status >= 400, f"status={status}")
else:
    check("reset code available for test", False, "no devOtp returned")

# ─────────────────────────────────────────────────────────────────────────────
section("5. Session handling: refresh, logout, revocation")

status, body = call("POST", "/api/v1/auth/refresh", {"refreshToken": citizen_refresh})
check("refresh returns a new session", status == 200 and (body or {}).get("accessToken"),
      f"status={status}")
rotated_refresh = (body or {}).get("refreshToken")
citizen_token = (body or {}).get("accessToken") or citizen_token

status, _ = call("POST", "/api/v1/auth/refresh", {"refreshToken": citizen_refresh})
check("old refresh token is rotated out", status >= 400, f"status={status}")

status, _ = call("POST", "/api/v1/auth/logout", {"refreshToken": rotated_refresh})
check("logout accepted", status in (200, 204), f"status={status}")

status, _ = call("POST", "/api/v1/auth/refresh", {"refreshToken": rotated_refresh})
check("refresh token revoked after logout", status >= 400, f"status={status}")

status, _ = call("GET", "/api/v1/me")
check("protected route rejects anonymous", status == 401, f"status={status}")

status, _ = call("GET", "/api/v1/me", token="not-a-real-token")
check("protected route rejects a garbage token", status == 401, f"status={status}")

# ─────────────────────────────────────────────────────────────────────────────
section("6. Reviewer accounts (Play submission dependency)")

reviewers = {
    "citizen": ("e2e.citizen@gmail.com", "E2eCitizen2026"),
    "partner": ("e2e.partner@gmail.com", "E2ePartner2026"),
    "mediator": ("e2e.mediator@gmail.com", "E2eMediator2026"),
}
tokens = {}
for name, (email, password) in reviewers.items():
    status, body = call("POST", "/api/v1/auth/password/login",
                        {"email": email, "password": password})
    ok = status == 200 and body and body.get("accessToken")
    check(f"reviewer {name} can sign in", ok, f"status={status} body={body}")
    if ok:
        tokens[name] = body["accessToken"]

for name, token in tokens.items():
    status, body = call("GET", "/api/v1/me", token=token)
    check(f"reviewer {name} email pre-verified",
          status == 200 and (body or {}).get("emailVerified") is True,
          f"status={status} body={body}")

if "citizen" in tokens:
    status, body = call("GET", "/api/v1/me", token=tokens["citizen"])
    check("online payments reported disabled via /me",
          (body or {}).get("onlinePaymentsEnabled") is False, f"body={body}")

if "partner" in tokens:
    status, body = call("GET", "/api/v1/helper/profile", token=tokens["partner"])
    check("reviewer partner KYC is APPROVED",
          status == 200 and (body or {}).get("kycStatus") == "APPROVED",
          f"status={status} body={body}")

# ─────────────────────────────────────────────────────────────────────────────
section("7. Geo: Madhapur autocomplete, details and Hyderabad routing")

cit = tokens.get("citizen")
if cit:
    status, body = call(
        "GET",
        f"/api/v1/geo/autocomplete?q=Madhapur&lat={HYD[0]}&lng={HYD[1]}",
        token=cit,
    )
    suggestions = (body or {}).get("suggestions") or []
    check("Madhapur autocomplete returns a selectable result",
          status == 200 and len(suggestions) > 0,
          f"status={status} provider={(body or {}).get('provider')} count={len(suggestions)}")

    if suggestions and suggestions[0].get("placeId"):
        place_id = suggestions[0]["placeId"].replace(":", "%3A")
        status, detail = call("GET", f"/api/v1/geo/place?placeId={place_id}", token=cit)
        result = (detail or {}).get("result") or {}
        check("selected autocomplete result resolves to map coordinates",
              status == 200 and result.get("lat") is not None and result.get("lng") is not None,
              f"status={status} body={detail}")

    status, route = call(
        "GET",
        f"/api/v1/geo/route?fromLat={HYD[0]}&fromLng={HYD[1]}&toLat=17.4483&toLng=78.3915",
        token=cit,
    )
    route_result = (route or {}).get("result") or {}
    check("Hyderabad route always returns ETA and distance",
          status == 200 and route_result.get("etaSeconds") is not None
          and route_result.get("distanceMeters") is not None,
          f"status={status} provider={(route or {}).get('provider')} body={route}")

# ─────────────────────────────────────────────────────────────────────────────
section("8. Booking: geofence and payment mode enforcement")

def task_body(lat, lng, mode="PAY_AFTER_SERVICE"):
    return {
        "title": "Help moving a sofa",
        "description": "Need a hand shifting a sofa between rooms.",
        "urgency": "NORMAL", "timeMinutes": 45, "budgetPaise": 45000,
        "lat": lat, "lng": lng,
        "addressText": "Hitech City, Hyderabad",
        "paymentCollectionMode": mode,
    }

if cit:
    status, body = call("POST", "/api/v1/tasks", task_body(*MUMBAI), token=cit)
    check("Mumbai booking rejected (outside service area)", status == 400,
          f"status={status} body={body}")

    status, body = call("POST", "/api/v1/tasks", task_body(*HYD, mode="ONLINE_PREPAID"), token=cit)
    check("ONLINE_PREPAID rejected with a clean 400 while payments are off",
          status == 400, f"status={status} body={body}")

    status, body = call("POST", "/api/v1/tasks", task_body(*HYD), token=cit)
    check("Hyderabad cash/UPI booking accepted", status == 200 and (body or {}).get("taskId"),
          f"status={status} body={body}")
    task_id = (body or {}).get("taskId")

    if task_id:
        status, body = call("GET", f"/api/v1/tasks/{task_id}", token=cit)
        check("citizen can read their own task", status == 200, f"status={status}")
        check("task defaulted to PAY_AFTER_SERVICE",
              (body or {}).get("paymentCollectionMode") == "PAY_AFTER_SERVICE", f"body={body}")

        # Authorization: another citizen must not be able to read it.
        other_email = f"other-{uuid.uuid4().hex[:8]}@gmail.com"
        s, other = call("POST", "/api/v1/auth/password/signup", {
            "email": other_email, "password": "Hyd2026Secure",
            "phone": f"9{uuid.uuid4().int % 900000000 + 100000000}",
            "displayName": "Other", "role": "BUYER"})
        if s == 200:
            status, _ = call("GET", f"/api/v1/tasks/{task_id}", token=other["accessToken"])
            check("another citizen cannot read someone else's task", status >= 400,
                  f"status={status}")

        # Role enforcement: a citizen must not be able to accept work.
        status, _ = call("POST", f"/api/v1/tasks/{task_id}/accept", token=cit)
        check("citizen cannot accept a task", status >= 400, f"status={status}")

        status, _ = call("POST", f"/api/v1/tasks/{task_id}/cancel",
                         {"reason": "changed my mind"}, token=cit)
        check("citizen can cancel their task", status == 200, f"status={status}")

# ─────────────────────────────────────────────────────────────────────────────
section("9. Partner surface")

partner = tokens.get("partner")
if partner:
    status, _ = call("PUT", "/api/v1/helper/online", {"online": True, "lat": HYD[0], "lng": HYD[1]},
                     token=partner)
    check("partner can go online", status in (200, 204), f"status={status}")

    status, body = call("GET", "/api/v1/tasks/available", token=partner)
    check("partner can list available work", status == 200 and isinstance(body, list),
          f"status={status}")

    status, _ = call("POST", "/api/v1/tasks", task_body(*HYD), token=partner)
    check("partner cannot create a task", status >= 400, f"status={status}")

# ─────────────────────────────────────────────────────────────────────────────
section("10. Matching: offer, decline, re-dispatch")

if cit and partner:
    status, body = call("POST", "/api/v1/tasks", task_body(*HYD), token=cit)
    match_task = (body or {}).get("taskId")
    offered = (body or {}).get("offeredTo") or []
    check("booking dispatches offers to the online partner", bool(match_task),
          f"status={status} body={body}")

    if match_task:
        # The decline endpoint is new — DECLINED was previously never written.
        status, _ = call("POST", f"/api/v1/tasks/{match_task}/decline", token=partner)
        check("partner can decline an offer", status in (200, 204), f"status={status}")

        status, _ = call("POST", f"/api/v1/tasks/{match_task}/decline", token=partner)
        check("declining twice is idempotent", status in (200, 204), f"status={status}")

        call("POST", f"/api/v1/tasks/{match_task}/cancel", {"reason": "test cleanup"}, token=cit)

# ─────────────────────────────────────────────────────────────────────────────
section("11. Full lifecycle: book → accept → arrive → start → complete → pay → rate")

if cit and partner:
    # Keep the suite repeatable after an interrupted prior run. Reviewer tasks
    # belong only to this isolated database, so cancel any unfinished fixture
    # before asking the partner to accept a new one.
    _, existing_tasks = call("GET", "/api/v1/tasks/my", token=cit)
    for existing in existing_tasks or []:
        if existing.get("status") not in ("COMPLETED", "CANCELLED"):
            call("POST", f"/api/v1/tasks/{existing.get('id')}/cancel",
                 {"reason": "e2e fixture cleanup"}, token=cit)

    call("PUT", "/api/v1/helper/online", {"online": True, "lat": HYD[0], "lng": HYD[1]}, token=partner)
    status, body = call("POST", "/api/v1/tasks", task_body(*HYD), token=cit)
    life_task = (body or {}).get("taskId")
    check("lifecycle task created", bool(life_task), f"status={status} body={body}")

    if life_task:
        status, body = call("POST", f"/api/v1/tasks/{life_task}/accept", token=partner)
        accepted = status == 200
        check("partner accepts the job", accepted, f"status={status} body={body}")

        if accepted:
            status, task = call("GET", f"/api/v1/tasks/{life_task}", token=cit)
            arrival_otp = (task or {}).get("arrivalOtp")
            completion_otp = (task or {}).get("completionOtp")
            check("citizen can see the arrival code", bool(arrival_otp), f"task={task}")

            status, _ = call("POST", f"/api/v1/tasks/{life_task}/status",
                             {"status": "STARTED", "otp": "123456"}, token=partner)
            check("static OTP 123456 rejected for starting work", status >= 400,
                  f"status={status}")

            # Default verification mode is PHOTO_AND_OTP. Prove the gate, then
            # exercise the real multipart upload and finish the entire lifecycle.
            status, body = call("POST", f"/api/v1/tasks/{life_task}/status",
                                {"status": "ARRIVED"}, token=partner)
            check("arrival blocked without the required selfie",
                  status == 400 and "selfie" in str(body).lower(),
                  f"status={status} body={body}")

            status, body = upload_selfie(life_task, "ARRIVAL", partner)
            check("partner uploads arrival selfie", status == 200,
                  f"status={status} body={body}")

            status, body = call("POST", f"/api/v1/tasks/{life_task}/status",
                                {"status": "ARRIVED"}, token=partner)
            arrived = status == 200
            check("partner marks arrival after selfie", arrived,
                  f"status={status} body={body}")

            if arrived and arrival_otp:
                status, _ = call("POST", f"/api/v1/tasks/{life_task}/status",
                                 {"status": "STARTED", "otp": arrival_otp}, token=partner)
                started = status == 200
                check("partner starts work with the real code", started, f"status={status}")

                if started and completion_otp:
                    status, body = upload_selfie(life_task, "COMPLETION", partner)
                    check("partner uploads completion selfie", status == 200,
                          f"status={status} body={body}")

                    status, _ = call("POST", f"/api/v1/tasks/{life_task}/status",
                                     {"status": "COMPLETED", "otp": completion_otp}, token=partner)
                    completed = status == 200
                    check("partner completes with the real code", completed, f"status={status}")

                    if completed:
                        status, body = call(
                            "POST", f"/api/v1/payments/tasks/{life_task}/direct-payment",
                            {"method": "CASH"}, token=partner)
                        check("partner confirms cash collection", status == 200,
                              f"status={status} body={body}")

                        status, _ = call("POST", f"/api/v1/tasks/{life_task}/rating",
                                         {"rating": 5.0, "comment": "Great work"}, token=cit)
                        check("citizen rates the completed job", status == 200, f"status={status}")

# ─────────────────────────────────────────────────────────────────────────────
section("12. Online payment endpoints refuse cleanly while disabled")

if cit:
    status, body = call("POST", "/api/v1/tasks", task_body(*HYD), token=cit)
    pay_task = (body or {}).get("taskId")
    if pay_task:
        req = urllib.request.Request(
            f"{BASE}/api/v1/payments/tasks/{pay_task}/orders", data=b"{}", method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", f"Bearer {cit}")
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                status, raw = resp.status, resp.read().decode()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read().decode()
        # The point: a clean 400, not a 500 about missing Razorpay credentials.
        check("payment order returns 400 (not 500) while payments are off",
              status == 400, f"status={status} body={raw[:200]}")
        call("POST", f"/api/v1/tasks/{pay_task}/cancel", {"reason": "cleanup"}, token=cit)

# ─────────────────────────────────────────────────────────────────────────────
section("13. Rate limiting")

blocked = False
for _ in range(15):
    status, _ = call("POST", "/api/v1/auth/password/login",
                     {"email": "ratelimit@gmail.com", "password": "WrongPass2026"})
    if status == 429:
        blocked = True
        break
check("brute-force login is rate limited", blocked, "no 429 after 15 attempts")

blocked = False
for _ in range(10):
    status, _ = call("POST", "/api/v1/auth/password/forgot", {"email": "ratelimit@gmail.com"})
    if status == 429:
        blocked = True
        break
check("forgot-password is rate limited", blocked, "no 429 after 10 attempts")

# ─────────────────────────────────────────────────────────────────────────────
print("\n" + "=" * 70)
print(f"PASSED: {len(PASSED)}    FAILED: {len(FAILED)}")
if FAILED:
    print("\nFailures:")
    for name, detail in FAILED:
        print(f"  - {name}: {detail}")
print("=" * 70)
sys.exit(1 if FAILED else 0)
