# Tester Guide: OTP Testing Procedures
## Updated for Production Security Configuration

**Last Updated:** 2026-07-17  
**Status:** Production environment now SECURED  
**Impact:** Testers can no longer retrieve OTP codes from API responses

---

## 🔒 What Changed?

### Previous Configuration (INSECURE - Deprecated)
```json
// POST /api/v1/auth/otp/start response
{
  "phone": "9000000101",
  "sent": true,
  "devOtp": "123456"  // ❌ CRITICAL VULNERABILITY: OTP exposed!
}
```

**Problem:** Any tester or attacker could bypass SMS authentication by reading the OTP from the API response.

### Current Configuration (SECURE - Active)
```json
// POST /api/v1/auth/otp/start response
{
  "phone": "9000000101",
  "sent": true,
  "devOtp": null  // ✅ SECURE: No OTP exposed
}
```

**Fix Applied:**
- Server configuration: `/etc/superheroo/api.env`
- Setting: `OTP_RETURN_IN_RESPONSE=false`
- Effective: Production environment (api.mysuperhero.xyz)

---

## 📱 Testing Methods for OTP Codes

### Method 1: Use Real SMS Messages (Recommended for Production Testing)

**When to use:** Testing on production environment (api.mysuperhero.xyz)

**Procedure:**
1. Ensure you have access to a real mobile phone with the test number
2. Trigger OTP request via the app or API:
   ```bash
   curl -X POST https://api.mysuperhero.xyz/api/v1/auth/otp/start \
     -H "Content-Type: application/json" \
     -d '{"phone": "9000000101", "role": "BUYER"}'
   ```
3. Check your phone for the SMS message
4. Enter the 6-digit OTP code in the app or verification endpoint

**Test Phone Numbers:**
- Buyer: `9000000101`
- Helper: `9000000102`
- Mediator: `9000000201`
- Admin: `9000000301`

**SMS Provider:** Exotel (primary) / Twilio (fallback)

**Expected Delivery Time:** 5-30 seconds

**Troubleshooting:**
- If SMS doesn't arrive within 30 seconds, check spam/blocked messages
- Verify phone number is registered in the system
- Check server logs for SMS delivery errors (see Method 2)

---

### Method 2: Access Server Logs via SSH (For Authorized Testers)

**When to use:** Debugging OTP delivery issues or automated testing

**Prerequisites:**
- SSH access to production server (168.144.64.250)
- Sudo privileges

**Procedure:**

1. **SSH to production server:**
   ```bash
   ssh username@168.144.64.250
   ```

2. **Monitor OTP generation in real-time:**
   ```bash
   sudo journalctl -u superheroo-api -f | grep OTP
   ```

3. **Trigger OTP request** (from another terminal/app)

4. **Observe log output:**
   ```
   2026-07-17 14:30:15 INFO  OtpService - Generated OTP for phone 9000000101: 742951
   2026-07-17 14:30:16 INFO  ExotelService - Sending SMS to 9000000101
   2026-07-17 14:30:17 INFO  ExotelService - SMS sent successfully: sid=SM1234567890
   ```

5. **Extract the OTP code** (e.g., `742951`) and use it for testing

**Search for specific phone OTP:**
```bash
sudo journalctl -u superheroo-api --since "5 minutes ago" | grep "9000000101" | grep OTP
```

**View last 100 OTP logs:**
```bash
sudo journalctl -u superheroo-api -n 100 | grep OTP
```

**Security Note:** 
- Server log access is restricted to authorized personnel only
- Do not share OTP codes from logs publicly
- Logs are rotated every 7 days

---

### Method 3: Use Staging/Development Environment (If Available)

**When to use:** Automated testing, CI/CD pipelines, non-production validation

**Configuration:**
- A separate staging environment should have `OTP_RETURN_IN_RESPONSE=true`
- **NEVER use this configuration in production**

**Example staging endpoint:**
```
https://staging.mysuperhero.xyz/api/v1/auth/otp/start
```

**Response (staging only):**
```json
{
  "phone": "9000000101",
  "sent": true,
  "devOtp": "123456"  // ✅ OK in staging, ❌ NEVER in production
}
```

**Status:** Staging environment not yet configured (see recommendations below)

---

## 🔧 Updating Test Scripts

### Old Code Pattern (No Longer Works in Production)
```python
# ❌ DEPRECATED - This will fail in production
def login_buyer(phone):
    resp = requests.post(f"{BASE_URL}/api/v1/auth/otp/start", 
                        json={"phone": phone, "role": "BUYER"})
    otp = resp.json().get("devOtp")  # Returns None in production!
    if not otp:
        raise Exception("OTP not returned")  # ❌ Will fail
    # Continue with verification...
```

### New Code Pattern (Production-Compatible)

**Option A: Manual OTP Input (Interactive Testing)**
```python
def login_buyer(phone):
    resp = requests.post(f"{BASE_URL}/api/v1/auth/otp/start",
                        json={"phone": phone, "role": "BUYER"})
    
    if resp.json().get("sent"):
        # Prompt tester for OTP from SMS
        otp = input(f"Enter OTP sent to {phone}: ")
        return verify_otp(phone, otp, "BUYER")
    else:
        raise Exception("OTP sending failed")
```

**Option B: Environment-Aware Testing**
```python
import os

def get_otp(phone, role):
    """Get OTP code using environment-appropriate method"""
    
    resp = requests.post(f"{BASE_URL}/api/v1/auth/otp/start",
                        json={"phone": phone, "role": role})
    
    # Check if we're in dev/staging environment
    otp_in_response = resp.json().get("devOtp")
    
    if otp_in_response:
        # Staging/dev environment - OTP in response
        print(f"✅ Dev mode: OTP retrieved from response")
        return otp_in_response
    else:
        # Production environment - must use alternative method
        env = os.environ.get("TEST_ENV", "production")
        
        if env == "production":
            # Option 1: Prompt for manual input
            otp = input(f"📱 Check SMS on {phone} and enter OTP: ")
            return otp
        else:
            # Option 2: Read from server logs (if SSH access available)
            print(f"⚠️  OTP not in response. Check server logs:")
            print(f"   sudo journalctl -u superheroo-api -f | grep {phone} | grep OTP")
            otp = input("Enter OTP from logs: ")
            return otp
```

**Option C: Skip OTP Tests in Production**
```python
import pytest
import os

@pytest.mark.skipif(
    os.environ.get("TEST_ENV") == "production",
    reason="OTP not available in production API responses"
)
def test_buyer_login():
    # Test code that requires OTP from API response
    pass
```

---

## 📋 Test Files That Need Updates

The following test scripts currently depend on `devOtp` field and need modification:

### 1. `/Backend/docs/testing/smoke_test_e2e.py`
**Lines:** 24, 51  
**Current code:**
```python
otp = start_resp.get("devOtp")
if not otp:
    print("FAIL: OTP not returned in response.")
```
**Required change:** Implement manual OTP input or skip in production

### 2. `/Backend/docs/testing/test_comprehensive_all_apps.py`
**Lines:** 77, 108  
**Current code:**
```python
otp = otp_data.get("devOtp") or otp_data.get("otp") or "123456"
```
**Required change:** Use fallback method or environment detection

### 3. `/Backend/docs/testing/vapt_security_audit.py`
**Lines:** 57-60  
**Current code:**
```python
otp = r.json().get("devOtp")
if not otp:
    raise Exception("Dev OTP was not returned. Make sure the server runs in dev mode.")
```
**Required change:** Update error message and testing approach

### 4. `/Backend/docs/testing/test_bulk_and_schedule.py`
**Lines:** 64-67, 125-132  
**Current code:**
```python
otp = data.get("devOtp") or data.get("otp")
if not otp:
    return None, None
```
**Note:** This file already has security checks that flag `devOtp` as CRITICAL issue

### 5. `/Backend/docs/testing/test_production_e2e_with_payments.py`
**Lines:** 190, 206  
**Current code:**
```python
otp = otp_data.get("devOtp") or otp_data.get("otp") or "123456"
```
**Required change:** Should not use hardcoded fallback OTP

---

## 🎯 Best Practices for Testers

### ✅ DO:
- **Use real SMS for production testing** - This validates the entire authentication flow
- **Keep test phone numbers handy** - Have physical access to devices with test numbers
- **Use server logs for debugging** - When SMS delivery fails, check logs for root cause
- **Document OTP issues** - Report SMS delivery problems immediately
- **Test OTP expiration** - Verify OTPs expire after expected time (default: 10 minutes)
- **Test rate limiting** - Verify max 5 OTP requests per phone per hour

### ❌ DON'T:
- **Don't hardcode OTP values** - `"123456"` is not a valid test strategy
- **Don't request `OTP_RETURN_IN_RESPONSE=true` in production** - This is a critical security vulnerability
- **Don't share OTP codes publicly** - Treat test OTPs like production secrets
- **Don't bypass OTP validation** - Test the actual authentication flow
- **Don't test with unregistered numbers** - Ensure test numbers are in the system

---

## 🚀 Recommended Improvements

### 1. Create Dedicated Staging Environment
**Current status:** Not available  
**Recommendation:** Set up `staging.mysuperhero.xyz` with:
- `OTP_RETURN_IN_RESPONSE=true` for automated testing
- Separate database from production
- Same infrastructure as production for realistic testing

### 2. Implement Test Phone Number Bypass
**Current status:** Not implemented  
**Recommendation:** Add a whitelist of test phone numbers that use fixed OTP codes:
```java
// Only in non-production environments
if (isTestPhoneNumber(phone) && !isProdEnvironment()) {
    return "000000";  // Fixed OTP for test numbers
}
```

### 3. Create OTP Testing Dashboard
**Current status:** Not available  
**Recommendation:** Admin panel feature to view recent OTP codes for test accounts:
- Restricted to admin users only
- Only shows OTPs for whitelisted test phone numbers
- Time-limited (OTPs expire after 10 minutes)
- Audit logging for security

### 4. Automated Test Environment Detection
**Current status:** Manual configuration  
**Recommendation:** Tests should auto-detect environment:
```python
def get_base_url():
    """Auto-detect test environment"""
    import socket
    hostname = socket.gethostname()
    
    if "production" in hostname or "api.mysuperhero.xyz" in hostname:
        return "https://api.mysuperhero.xyz", "production"
    else:
        return "http://localhost:8080", "development"
```

---

## 📞 Support and Questions

**For OTP Testing Issues:**
- Check this guide first
- Verify SMS delivery status in logs
- Contact DevOps team for SSH access requests
- Report persistent SMS delivery failures immediately

**For Security Concerns:**
- Never expose OTP codes in public documentation
- Report any `devOtp` field exposure in production immediately
- Escalate authentication bypass attempts to security team

**Document Maintainer:** Production Readiness Team  
**Last Security Audit:** 2026-07-17  
**Next Review:** Before next production deployment

---

## ✅ Verification Checklist

Before running tests in production, verify:

- [ ] I understand that `devOtp` is no longer available in production
- [ ] I have access to real SMS for test phone numbers
- [ ] I have updated my test scripts to handle OTP appropriately
- [ ] I have SSH access to server logs (if needed for debugging)
- [ ] I will not request `OTP_RETURN_IN_RESPONSE=true` in production
- [ ] I will report any security issues immediately

---

**This guide is part of the Production Readiness Testing initiative (Task 1.5)**

**Related Documentation:**
- Task 1.4 Verification: `/Backend/.kiro/specs/production-readiness-testing/TASK_1.4_VERIFICATION.md`
- Security Requirements: Requirement 9 (OTP Rate Limiting Security Testing)
- Test Scripts: `/Backend/docs/testing/`
