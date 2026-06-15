#!/usr/bin/env python3
import sys
import time
import requests

API_BASE = "https://api.mysuperhero.xyz"
BUYER_PHONE = "9000000101"
HELPER_PHONE = "9000000102"

def main():
    print("=== SuperHerooo API E2E Smoke Test ===")
    print(f"Target API Base: {API_BASE}")

    # 1. Start Buyer OTP
    print("\n[Step 1] Starting OTP for Buyer...")
    payload = {"phone": BUYER_PHONE, "role": "BUYER", "channel": None}
    r = requests.post(f"{API_BASE}/api/v1/auth/otp/start", json=payload)
    if r.status_code != 200:
        print(f"FAIL: Start OTP failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    
    start_resp = r.json()
    print("Start OTP response:", start_resp)
    otp = start_resp.get("devOtp")
    if not otp:
        print("FAIL: OTP not returned in response. Is dev mode / returnOtpInResponse enabled?")
        sys.exit(1)

    # 2. Verify Buyer OTP
    print("\n[Step 2] Verifying OTP for Buyer...")
    verify_payload = {"phone": BUYER_PHONE, "otp": otp, "role": "BUYER"}
    r = requests.post(f"{API_BASE}/api/v1/auth/otp/verify", json=verify_payload)
    if r.status_code != 200:
        print(f"FAIL: Verify OTP failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    
    buyer_auth = r.json()
    buyer_token = buyer_auth.get("accessToken")
    print("Buyer logged in successfully! Token starts with:", buyer_token[:15])

    # 3. Start Helper OTP
    print("\n[Step 3] Starting OTP for Helper...")
    payload = {"phone": HELPER_PHONE, "role": "HELPER", "channel": None}
    r = requests.post(f"{API_BASE}/api/v1/auth/otp/start", json=payload)
    if r.status_code != 200:
        print(f"FAIL: Start OTP failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    
    start_resp = r.json()
    print("Start OTP response:", start_resp)
    otp = start_resp.get("devOtp")
    if not otp:
        print("FAIL: OTP not returned in response. Is dev mode / returnOtpInResponse enabled?")
        sys.exit(1)

    # 4. Verify Helper OTP
    print("\n[Step 4] Verifying OTP for Helper...")
    verify_payload = {"phone": HELPER_PHONE, "otp": otp, "role": "HELPER"}
    r = requests.post(f"{API_BASE}/api/v1/auth/otp/verify", json=verify_payload)
    if r.status_code != 200:
        print(f"FAIL: Verify OTP failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    
    helper_auth = r.json()
    helper_token = helper_auth.get("accessToken")
    print("Helper logged in successfully! Token starts with:", helper_token[:15])

    buyer_headers = {"Authorization": f"Bearer {buyer_token}", "Content-Type": "application/json"}
    helper_headers = {"Authorization": f"Bearer {helper_token}", "Content-Type": "application/json"}

    # 5. Set Helper Online
    print("\n[Step 5] Setting Helper Online...")
    online_payload = {"online": True, "lat": 12.9352, "lng": 77.6245}
    r = requests.put(f"{API_BASE}/api/v1/helper/online", json=online_payload, headers={"Authorization": f"Bearer {helper_token}"})
    if r.status_code not in (200, 204):
        print(f"FAIL: Setting helper online failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    print("Helper is now online.")

    # 6. Create Task as Buyer
    print("\n[Step 6] Creating Task as Buyer...")
    task_payload = {
        "title": "Smoke Test Task",
        "description": "Validation of task lifecycle end-to-end",
        "urgency": "NORMAL",
        "timeMinutes": 10,
        "budgetPaise": 5000,
        "lat": 12.9352,
        "lng": 77.6245,
        "addressText": "Bangalore Office"
    }
    r = requests.post(f"{API_BASE}/api/v1/tasks", json=task_payload, headers=buyer_headers)
    if r.status_code != 200:
        print(f"FAIL: Task creation failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    
    task_resp = r.json()
    task_id = task_resp.get("taskId")
    print(f"Task created successfully! Task ID: {task_id}")

    # Give matching service a brief moment
    time.sleep(1)

    # 7. Helper polls available tasks
    print("\n[Step 7] Helper polling available tasks...")
    r = requests.get(f"{API_BASE}/api/v1/tasks/available", headers={"Authorization": f"Bearer {helper_token}"})
    if r.status_code != 200:
        print(f"FAIL: Polling available tasks failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    
    available_tasks = r.json()
    task_ids = [t.get("id") for t in available_tasks]
    print("Available task IDs:", task_ids)
    if task_id not in task_ids:
        print(f"WARNING: Created task {task_id} not found in available list. Proceeding with direct accept attempt anyway...")

    # 8. Helper accepts task
    print(f"\n[Step 8] Helper accepting task {task_id}...")
    r = requests.post(f"{API_BASE}/api/v1/tasks/{task_id}/accept", json={}, headers={"Authorization": f"Bearer {helper_token}"})
    if r.status_code != 200:
        print(f"FAIL: Accept task failed with status {r.status_code}: {r.text}")
        sys.exit(1)
    print("Task accepted successfully! Current task state:")
    task_details = r.json()
    print(f"Status: {task_details.get('status')}, Assigned Helper: {task_details.get('assignedHelperId')}")

    # 9. Buyer fetches task to read OTPs
    print("\n[Step 9] Buyer fetching task details to retrieve OTPs...")
    r = requests.get(f"{API_BASE}/api/v1/tasks/{task_id}", headers=buyer_headers)
    if r.status_code != 200:
        print(f"FAIL: Fetching task failed: {r.text}")
        sys.exit(1)
    
    buyer_task_details = r.json()
    arrival_otp = buyer_task_details.get("arrivalOtp")
    completion_otp = buyer_task_details.get("completionOtp")
    print(f"Retrieved OTPs -> Arrival: {arrival_otp}, Completion: {completion_otp}")
    if not arrival_otp or not completion_otp:
        print("FAIL: OTPs missing in buyer task details!")
        sys.exit(1)

    # 10. Helper uploads Arrival Selfie
    print("\n[Step 10] Helper uploading Arrival Selfie...")
    dummy_selfie = ("arrival_selfie.jpg", b"fake_jpeg_data", "image/jpeg")
    selfie_files = {"selfie": dummy_selfie}
    selfie_data = {
        "stage": "ARRIVAL",
        "lat": "12.9352",
        "lng": "77.6245",
        "addressText": "Bangalore Office",
        "capturedAt": "2026-06-15T12:00:00Z"
    }
    r = requests.post(
        f"{API_BASE}/api/v1/tasks/{task_id}/selfie",
        data=selfie_data,
        files=selfie_files,
        headers={"Authorization": f"Bearer {helper_token}"}
    )
    if r.status_code != 200:
        print(f"FAIL: Arrival selfie upload failed: {r.text}")
        sys.exit(1)
    print("Arrival selfie uploaded successfully.")

    # 11. Helper marks ARRIVED
    print("\n[Step 11] Helper marking ARRIVED...")
    status_payload = {"status": "ARRIVED", "otp": None}
    r = requests.post(f"{API_BASE}/api/v1/tasks/{task_id}/status", json=status_payload, headers=helper_headers)
    if r.status_code != 200:
        print(f"FAIL: Transition to ARRIVED failed: {r.text}")
        sys.exit(1)
    print("Task state updated to ARRIVED.")

    # 12. Helper marks STARTED using arrival OTP
    print("\n[Step 12] Helper marking STARTED using OTP...")
    status_payload = {"status": "STARTED", "otp": arrival_otp}
    r = requests.post(f"{API_BASE}/api/v1/tasks/{task_id}/status", json=status_payload, headers=helper_headers)
    if r.status_code != 200:
        print(f"FAIL: Transition to STARTED failed: {r.text}")
        sys.exit(1)
    print("Task state updated to STARTED. Work is in progress.")

    # 13. Send chat messages between Buyer and Helper
    print("\n[Step 13] Exchanging chat messages...")
    r = requests.post(f"{API_BASE}/api/v1/tasks/{task_id}/chat/messages", json={"message": "Hi, I am on my way!"}, headers=helper_headers)
    if r.status_code != 200:
        print(f"FAIL: Helper sending message failed: {r.text}")
        sys.exit(1)
    print("Helper sent message.")

    r = requests.post(f"{API_BASE}/api/v1/tasks/{task_id}/chat/messages", json={"message": "Thanks, see you soon."}, headers=buyer_headers)
    if r.status_code != 200:
        print(f"FAIL: Buyer sending message failed: {r.text}")
        sys.exit(1)
    print("Buyer sent message.")

    r = requests.get(f"{API_BASE}/api/v1/tasks/{task_id}/chat/messages", headers=helper_headers)
    if r.status_code != 200:
        print(f"FAIL: Listing chat messages failed: {r.text}")
        sys.exit(1)
    messages = r.json()
    print("Chat conversation history:")
    for msg in messages:
        print(f" - {msg.get('senderRole')}: {msg.get('message')}")

    # 14. Helper uploads Completion Selfie
    print("\n[Step 14] Helper uploading Completion Selfie...")
    dummy_selfie = ("completion_selfie.jpg", b"fake_jpeg_data_completion", "image/jpeg")
    selfie_files = {"selfie": dummy_selfie}
    selfie_data = {
        "stage": "COMPLETION",
        "lat": "12.9352",
        "lng": "77.6245",
        "addressText": "Bangalore Office",
        "capturedAt": "2026-06-15T12:15:00Z"
    }
    r = requests.post(
        f"{API_BASE}/api/v1/tasks/{task_id}/selfie",
        data=selfie_data,
        files=selfie_files,
        headers={"Authorization": f"Bearer {helper_token}"}
    )
    if r.status_code != 200:
        print(f"FAIL: Completion selfie upload failed: {r.text}")
        sys.exit(1)
    print("Completion selfie uploaded successfully.")

    # 15. Helper marks COMPLETED using completion OTP
    print("\n[Step 15] Helper marking COMPLETED using OTP...")
    status_payload = {"status": "COMPLETED", "otp": completion_otp}
    r = requests.post(f"{API_BASE}/api/v1/tasks/{task_id}/status", json=status_payload, headers=helper_headers)
    if r.status_code != 200:
        print(f"FAIL: Transition to COMPLETED failed: {r.text}")
        sys.exit(1)
    print("Task state updated to COMPLETED.")

    # 16. Buyer rates the completed task
    print("\n[Step 16] Buyer rating the task...")
    rating_payload = {"rating": 5.0, "comment": "Amazing experience! Very fast."}
    r = requests.post(f"{API_BASE}/api/v1/tasks/{task_id}/rating", json=rating_payload, headers=buyer_headers)
    if r.status_code != 200:
        print(f"FAIL: Rating task failed: {r.text}")
        sys.exit(1)
    print("Task rated successfully by buyer.")

    print("\n==============================================")
    print("SUCCESS: ALL END-TO-END FLOW CHECKS PASSED!!!")
    print("==============================================")

if __name__ == "__main__":
    main()
