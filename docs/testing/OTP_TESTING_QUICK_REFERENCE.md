# OTP Testing Quick Reference Card
## Production Environment (api.mysuperhero.xyz)

> **🔒 IMPORTANT:** As of 2026-07-17, OTP codes are NO LONGER available in API responses for security reasons.

---

## Quick Decision Tree

```
Need to test OTP authentication?
│
├─ Testing on PRODUCTION? ─────┬─ Have test phone access? ─── Use Method 1: Real SMS
│                              └─ Need to debug SMS issue? ─── Use Method 2: Server Logs
│
└─ Testing on STAGING/DEV? ──── Wait for staging environment (see recommendations)
```

---

## Method 1: Real SMS (Production)

**Use when:** Running tests on production with real devices

```bash
# 1. Request OTP
curl -X POST https://api.mysuperhero.xyz/api/v1/auth/otp/start \
  -H "Content-Type: application/json" \
  -d '{"phone": "9000000101", "role": "BUYER"}'

# 2. Response (OTP NOT included)
{
  "phone": "9000000101",
  "sent": true,
  "devOtp": null  ← OTP NOT HERE!
}

# 3. Check your phone for SMS (5-30 seconds)
# 4. Use the 6-digit code from SMS
```

**Test Numbers:**
- Buyer: `9000000101`
- Helper: `9000000102`  
- Mediator: `9000000201`
- Admin: `9000000301`

---

## Method 2: Server Logs (SSH Access Required)

**Use when:** Debugging SMS delivery or automated testing

```bash
# SSH to server
ssh username@168.144.64.250

# Watch OTP logs in real-time
sudo journalctl -u superheroo-api -f | grep OTP

# Search for specific phone
sudo journalctl -u superheroo-api --since "5 minutes ago" | grep "9000000101" | grep OTP
```

**Example output:**
```
2026-07-17 14:30:15 INFO OtpService - Generated OTP for phone 9000000101: 742951
                                                                          ^^^^^^
                                                                       USE THIS CODE
```

---

## Code Snippet for Tests

### ✅ Production-Compatible Code

```python
def login_for_testing(phone, role):
    """Production-safe login that prompts for OTP"""
    
    # Request OTP
    resp = requests.post(f"{BASE_URL}/api/v1/auth/otp/start",
                        json={"phone": phone, "role": role})
    
    if not resp.json().get("sent"):
        raise Exception("OTP sending failed")
    
    # Get OTP from user
    print(f"📱 OTP sent to {phone}")
    otp = input("Enter OTP from SMS: ")
    
    # Verify OTP and get token
    verify_resp = requests.post(f"{BASE_URL}/api/v1/auth/otp/verify",
                               json={"phone": phone, "otp": otp})
    
    return verify_resp.json()["token"]
```

### ❌ Old Code (Doesn't Work Anymore)

```python
# DON'T USE THIS - devOtp is always null in production
otp = resp.json().get("devOtp")  # Returns None!
```

---

## Test Script Migration Checklist

- [ ] Remove all `devOtp` field accesses
- [ ] Add manual OTP input for interactive tests
- [ ] Skip OTP-dependent tests in production CI/CD
- [ ] Use server logs for automated debugging
- [ ] Update error messages to reflect new process

---

## Files to Update

| File | Lines | Status |
|------|-------|--------|
| `smoke_test_e2e.py` | 24, 51 | ⚠️ Update required |
| `test_comprehensive_all_apps.py` | 77, 108 | ⚠️ Update required |
| `vapt_security_audit.py` | 57-60 | ⚠️ Update required |
| `test_bulk_and_schedule.py` | 64-67 | ⚠️ Update required |
| `test_production_e2e_with_payments.py` | 190, 206 | ⚠️ Update required |

---

## Common Errors

### Error: "OTP not returned in response"
**Cause:** Old test code expecting `devOtp` field  
**Fix:** Update test to use real SMS or server logs

### Error: SMS not received
**Check:**
1. Phone number registered in system?
2. Server logs show SMS sent?
3. SMS provider (Exotel) working?
4. Rate limit not exceeded? (5 per hour)

### Error: "Invalid OTP"
**Check:**
1. OTP entered correctly? (6 digits)
2. OTP expired? (10 minute expiry)
3. Using latest OTP? (old codes invalid)

---

## Security Reminders

| ⚠️ NEVER | ✅ ALWAYS |
|----------|-----------|
| Commit OTP codes to git | Use real SMS in production |
| Share OTP codes publicly | Treat test OTPs as secrets |
| Enable `devOtp` in production | Use server logs for debugging |
| Hardcode OTP values | Test actual authentication flow |

---

## Need Help?

📖 **Full Documentation:** `TESTER_GUIDE_OTP_PROCEDURES.md`  
🔒 **Security Issue?** Report immediately to DevOps team  
🐛 **SMS Delivery Problem?** Check server logs first  
🔑 **SSH Access?** Request from infrastructure team

---

**Last Updated:** 2026-07-17  
**Change:** Task 1.1-1.4 completed - OTP security hardening  
**Status:** ✅ Production is now SECURE
