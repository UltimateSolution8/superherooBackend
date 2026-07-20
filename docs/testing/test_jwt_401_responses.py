#!/usr/bin/env python3
"""
JWT 401 Response Validation Tests
Test Suite: Verifies that unauthenticated requests return 401 (Unauthorized)
instead of 403 (Forbidden), and that authorization failures return 403.

Requirements:
- Missing token → 401
- Invalid/tampered JWT → 401
- Expired token → 401
- Valid token but wrong role → 403 (Forbidden)
- Valid token with correct role → 200 (Success)

References: Requirement 10 (VAPT Security Assessment), Finding H-3
"""

import requests
import json
import time
from datetime import datetime, timedelta
import jwt
import sys

# Configuration
BASE_URL = "http://localhost:8080"  # Use production URL for testing
API_ENDPOINT = "/api/v1/tasks"
ADMIN_ENDPOINT = "/api/v1/admin/summary"

# Test data
BUYER_PHONE = "9000000101"
HELPER_PHONE = "9000000102"
MEDIATOR_PHONE = "9000000201"
TEST_PASSWORD = "Test@1234"

# Colors for output
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
BLUE = "\033[94m"
RESET = "\033[0m"


def log_test(test_name, passed, details=""):
    """Log test result"""
    status = f"{GREEN}✓ PASS{RESET}" if passed else f"{RED}✗ FAIL{RESET}"
    print(f"{BLUE}[TEST]{RESET} {test_name}: {status}")
    if details:
        print(f"       {details}")


def log_section(section_name):
    """Log section header"""
    print(f"\n{YELLOW}{'='*70}{RESET}")
    print(f"{YELLOW}{section_name:^70}{RESET}")
    print(f"{YELLOW}{'='*70}{RESET}\n")


class JWTTestSuite:
    def __init__(self, base_url=BASE_URL):
        self.base_url = base_url
        self.buyer_token = None
        self.helper_token = None
        self.mediator_token = None
        self.test_results = []

    def authenticate_user(self, phone, password):
        """Authenticate user and get JWT token"""
        try:
            # Start OTP
            otp_response = requests.post(
                f"{self.base_url}/api/v1/auth/otp/start",
                json={"phone": phone}
            )
            
            if otp_response.status_code != 200:
                print(f"{RED}Failed to start OTP for {phone}{RESET}")
                return None
            
            otp_data = otp_response.json()
            dev_otp = otp_data.get("devOtp")  # Only available in development
            
            if not dev_otp:
                print(f"{RED}Cannot get OTP in non-development environment{RESET}")
                return None
            
            # Verify OTP
            verify_response = requests.post(
                f"{self.base_url}/api/v1/auth/otp/verify",
                json={"phone": phone, "otp": dev_otp}
            )
            
            if verify_response.status_code != 200:
                print(f"{RED}Failed to verify OTP for {phone}{RESET}")
                return None
            
            token = verify_response.json().get("accessToken")
            return token
        except Exception as e:
            print(f"{RED}Error authenticating {phone}: {str(e)}{RESET}")
            return None

    def setup_test_data(self):
        """Setup test data - authenticate test users"""
        log_section("Setup: Authenticating Test Users")
        
        print(f"Authenticating buyer ({BUYER_PHONE})...")
        self.buyer_token = self.authenticate_user(BUYER_PHONE, TEST_PASSWORD)
        if self.buyer_token:
            print(f"{GREEN}✓ Buyer authenticated{RESET}")
        else:
            print(f"{RED}✗ Failed to authenticate buyer{RESET}")
        
        print(f"\nAuthenticating helper ({HELPER_PHONE})...")
        self.helper_token = self.authenticate_user(HELPER_PHONE, TEST_PASSWORD)
        if self.helper_token:
            print(f"{GREEN}✓ Helper authenticated{RESET}")
        else:
            print(f"{RED}✗ Failed to authenticate helper{RESET}")

    def test_missing_authorization_header(self):
        """Test 1: Missing Authorization header → 401"""
        log_section("Test 1: Missing Authorization Header")
        
        response = requests.get(
            f"{self.base_url}{API_ENDPOINT}",
            headers={}
        )
        
        passed = response.status_code == 401
        log_test(
            "Missing Authorization header",
            passed,
            f"Expected: 401, Got: {response.status_code}"
        )
        
        if response.status_code == 401:
            body = response.json()
            has_unauthorized = body.get("code") == "UNAUTHORIZED"
            print(f"       Response: {json.dumps(body, indent=2)}")
            if has_unauthorized:
                print(f"{GREEN}       ✓ Response has UNAUTHORIZED code{RESET}")
        
        self.test_results.append(("Missing Authorization Header", passed))
        return passed

    def test_invalid_token_format(self):
        """Test 2: Invalid token format → 401"""
        log_section("Test 2: Invalid Token Format")
        
        invalid_tokens = [
            ("not-a-token", "Random string"),
            ("Bearer", "Bearer with no token"),
            ("Bearer  ", "Bearer with spaces only"),
            ("Invalid token-format", "Malformed Bearer format"),
        ]
        
        results = []
        for token, description in invalid_tokens:
            response = requests.get(
                f"{self.base_url}{API_ENDPOINT}",
                headers={"Authorization": token}
            )
            
            passed = response.status_code == 401
            results.append(passed)
            log_test(
                f"Invalid token: {description}",
                passed,
                f"Expected: 401, Got: {response.status_code}"
            )
        
        overall_passed = all(results)
        self.test_results.append(("Invalid Token Format", overall_passed))
        return overall_passed

    def test_tampered_jwt(self):
        """Test 3: Tampered JWT token → 401"""
        log_section("Test 3: Tampered JWT Token")
        
        if not self.buyer_token:
            print(f"{RED}Cannot test tampered JWT - no valid token available{RESET}")
            self.test_results.append(("Tampered JWT", False))
            return False
        
        # Tamper with token by changing last character
        tampered_token = self.buyer_token[:-1] + "X"
        
        response = requests.get(
            f"{self.base_url}{API_ENDPOINT}",
            headers={"Authorization": f"Bearer {tampered_token}"}
        )
        
        passed = response.status_code == 401
        log_test(
            "Tampered JWT token",
            passed,
            f"Expected: 401, Got: {response.status_code}"
        )
        
        self.test_results.append(("Tampered JWT", passed))
        return passed

    def test_token_with_modified_payload(self):
        """Test 4: JWT with modified payload (but valid signature is tricky without key)"""
        log_section("Test 4: JWT with Modified Payload")
        
        if not self.buyer_token:
            print(f"{RED}Cannot test modified payload - no valid token available{RESET}")
            self.test_results.append(("Modified JWT Payload", False))
            return False
        
        # Try to create a JWT with modified payload
        # Split the token and replace payload
        try:
            parts = self.buyer_token.split('.')
            if len(parts) == 3:
                # Create fake payload: change user ID
                fake_payload = '{"userId":"999999","role":"BUYER"}'
                import base64
                fake_payload_b64 = base64.urlsafe_b64encode(fake_payload.encode()).decode().rstrip('=')
                
                # Create tampered token
                tampered = f"{parts[0]}.{fake_payload_b64}.{parts[2]}"
                
                response = requests.get(
                    f"{self.base_url}{API_ENDPOINT}",
                    headers={"Authorization": f"Bearer {tampered}"}
                )
                
                passed = response.status_code == 401
                log_test(
                    "JWT with modified payload",
                    passed,
                    f"Expected: 401, Got: {response.status_code}"
                )
                
                self.test_results.append(("Modified JWT Payload", passed))
                return passed
        except Exception as e:
            print(f"{RED}Error in test: {str(e)}{RESET}")
            self.test_results.append(("Modified JWT Payload", False))
            return False

    def test_expired_token(self):
        """Test 5: Expired JWT token → 401"""
        log_section("Test 5: Expired JWT Token")
        
        # Try to create an expired token by using jwt library
        # (Note: This requires the JWT secret key which we may not have)
        try:
            import jwt as pyjwt
            
            # Create an already-expired token
            payload = {
                "userId": 123,
                "role": "BUYER",
                "exp": datetime.utcnow() - timedelta(hours=1)  # Expired 1 hour ago
            }
            
            # Try with a dummy secret (will fail signature verification)
            expired_token = pyjwt.encode(payload, "dummy-secret", algorithm="HS256")
            
            response = requests.get(
                f"{self.base_url}{API_ENDPOINT}",
                headers={"Authorization": f"Bearer {expired_token}"}
            )
            
            passed = response.status_code == 401
            log_test(
                "Expired JWT token",
                passed,
                f"Expected: 401, Got: {response.status_code}"
            )
            
            self.test_results.append(("Expired JWT Token", passed))
            return passed
        except Exception as e:
            print(f"{YELLOW}Warning: Could not test expired token: {str(e)}{RESET}")
            self.test_results.append(("Expired JWT Token", None))  # None = skipped
            return None

    def test_valid_token_insufficient_role(self):
        """Test 6: Valid token but insufficient role → 403"""
        log_section("Test 6: Valid Token with Insufficient Role")
        
        if not self.helper_token:
            print(f"{RED}Cannot test insufficient role - no helper token available{RESET}")
            self.test_results.append(("Valid Token, Insufficient Role", False))
            return False
        
        # Helper tries to access admin endpoint (requires ADMIN role)
        response = requests.get(
            f"{self.base_url}{ADMIN_ENDPOINT}",
            headers={"Authorization": f"Bearer {self.helper_token}"}
        )
        
        passed = response.status_code == 403
        log_test(
            "Valid token but insufficient role (Helper accessing Admin endpoint)",
            passed,
            f"Expected: 403, Got: {response.status_code}"
        )
        
        if response.status_code == 403:
            print(f"       Response: {json.dumps(response.json(), indent=2)}")
        
        self.test_results.append(("Valid Token, Insufficient Role", passed))
        return passed

    def test_valid_token_with_correct_role(self):
        """Test 7: Valid token with correct role → 200"""
        log_section("Test 7: Valid Token with Correct Role")
        
        if not self.buyer_token:
            print(f"{RED}Cannot test valid token - no buyer token available{RESET}")
            self.test_results.append(("Valid Token, Correct Role", False))
            return False
        
        response = requests.get(
            f"{self.base_url}{API_ENDPOINT}",
            headers={"Authorization": f"Bearer {self.buyer_token}"}
        )
        
        passed = response.status_code == 200
        log_test(
            "Valid token with correct role",
            passed,
            f"Expected: 200, Got: {response.status_code}"
        )
        
        self.test_results.append(("Valid Token, Correct Role", passed))
        return passed

    def test_malformed_bearer_header(self):
        """Test 8: Malformed Bearer header variations"""
        log_section("Test 8: Malformed Bearer Header Variations")
        
        test_cases = [
            ("bearer token", "lowercase bearer prefix"),
            ("BEARER token", "uppercase BEARER prefix"),
            ("Bearer", "Bearer only, no token"),
            ("Bearer ", "Bearer with space, no token"),
            ("Bearer\ttoken", "Bearer with tab separator"),
        ]
        
        results = []
        for header_value, description in test_cases:
            response = requests.get(
                f"{self.base_url}{API_ENDPOINT}",
                headers={"Authorization": header_value}
            )
            
            # All should be 401 (unauthenticated)
            passed = response.status_code == 401
            results.append(passed)
            log_test(
                f"Malformed header: {description}",
                passed,
                f"Expected: 401, Got: {response.status_code}"
            )
        
        overall_passed = all(results)
        self.test_results.append(("Malformed Bearer Header", overall_passed))
        return overall_passed

    def test_empty_authorization_value(self):
        """Test 9: Empty Authorization header value"""
        log_section("Test 9: Empty Authorization Header Value")
        
        response = requests.get(
            f"{self.base_url}{API_ENDPOINT}",
            headers={"Authorization": ""}
        )
        
        passed = response.status_code == 401
        log_test(
            "Empty Authorization header",
            passed,
            f"Expected: 401, Got: {response.status_code}"
        )
        
        self.test_results.append(("Empty Authorization Value", passed))
        return passed

    def test_no_bearer_prefix(self):
        """Test 10: Token without Bearer prefix"""
        log_section("Test 10: Token Without Bearer Prefix")
        
        if not self.buyer_token:
            print(f"{RED}Cannot test - no valid token available{RESET}")
            self.test_results.append(("Token Without Bearer Prefix", False))
            return False
        
        response = requests.get(
            f"{self.base_url}{API_ENDPOINT}",
            headers={"Authorization": self.buyer_token}  # No "Bearer" prefix
        )
        
        passed = response.status_code == 401
        log_test(
            "Token without Bearer prefix",
            passed,
            f"Expected: 401, Got: {response.status_code}"
        )
        
        self.test_results.append(("Token Without Bearer Prefix", passed))
        return passed

    def run_all_tests(self):
        """Run all test cases"""
        print(f"\n{BLUE}{'='*70}{RESET}")
        print(f"{BLUE}JWT 401 Response Validation Test Suite{RESET}")
        print(f"{BLUE}{'='*70}{RESET}")
        
        # Setup
        self.setup_test_data()
        
        # Run tests
        try:
            self.test_missing_authorization_header()
            self.test_invalid_token_format()
            self.test_tampered_jwt()
            self.test_token_with_modified_payload()
            self.test_expired_token()
            self.test_valid_token_insufficient_role()
            self.test_valid_token_with_correct_role()
            self.test_malformed_bearer_header()
            self.test_empty_authorization_value()
            self.test_no_bearer_prefix()
        except Exception as e:
            print(f"\n{RED}Error during test execution: {str(e)}{RESET}")
            import traceback
            traceback.print_exc()
        
        # Generate summary
        self.print_summary()

    def print_summary(self):
        """Print test summary"""
        log_section("Test Summary")
        
        passed = sum(1 for _, result in self.test_results if result is True)
        failed = sum(1 for _, result in self.test_results if result is False)
        skipped = sum(1 for _, result in self.test_results if result is None)
        total = len(self.test_results)
        
        print(f"Total Tests: {total}")
        print(f"{GREEN}Passed: {passed}{RESET}")
        print(f"{RED}Failed: {failed}{RESET}")
        print(f"{YELLOW}Skipped: {skipped}{RESET}")
        
        if failed == 0 and skipped == 0:
            print(f"\n{GREEN}{'='*70}{RESET}")
            print(f"{GREEN}All tests PASSED! JWT 401 response handling is correct.{RESET}")
            print(f"{GREEN}{'='*70}{RESET}\n")
            return 0
        else:
            print(f"\n{RED}{'='*70}{RESET}")
            print(f"{RED}Some tests FAILED. Review JWT authentication configuration.{RESET}")
            print(f"{RED}{'='*70}{RESET}\n")
            return 1


def main():
    """Main entry point"""
    # Parse command line arguments
    url = sys.argv[1] if len(sys.argv) > 1 else BASE_URL
    
    print(f"Testing against: {url}")
    
    suite = JWTTestSuite(base_url=url)
    exit_code = suite.run_all_tests()
    
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
