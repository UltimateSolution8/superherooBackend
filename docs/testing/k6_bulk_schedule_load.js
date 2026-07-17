/**
 * k6 Load Test: Bulk Booking & Schedule Later
 * Usage: k6 run k6_bulk_schedule_load.js
 * Safe for production: 10 VUs, 30 seconds
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const API_BASE = __ENV.API_BASE || 'https://api.mysuperhero.xyz';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@helpinminutes.app';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'Admin@12345';

const failures = new Counter('flow_failures');
const batchPreviewTrend = new Trend('batch_preview_ms');
const batchCreateTrend = new Trend('batch_create_ms');
const batchSummaryTrend = new Trend('batch_summary_ms');
const scheduleTaskTrend = new Trend('schedule_task_ms');

export const options = {
  scenarios: {
    bulkAndSchedule: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '10s', target: 5 },
        { duration: '20s', target: 10 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
    batch_preview_ms: ['p(95)<500'],
    batch_create_ms: ['p(95)<3000'],
    batch_summary_ms: ['p(95)<500'],
    schedule_task_ms: ['p(95)<1000'],
    flow_failures: ['count<10'],
  },
};

function authHeaders(token) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

function loginOtp(phone, role) {
  const startRes = http.post(`${API_BASE}/api/v1/auth/otp/start`,
    JSON.stringify({ phone, role }), { headers: { 'Content-Type': 'application/json' } });
  if (startRes.status !== 200) return null;
  const otp = startRes.json('devOtp');
  if (!otp) return null;
  const verifyRes = http.post(`${API_BASE}/api/v1/auth/otp/verify`,
    JSON.stringify({ phone, otp, role }), { headers: { 'Content-Type': 'application/json' } });
  return verifyRes.status === 200 ? verifyRes.json('accessToken') : null;
}

export function setup() {
  const adminRes = http.post(`${API_BASE}/api/v1/auth/password/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (adminRes.status !== 200) throw new Error('Admin login failed');
  const adminToken = adminRes.json('accessToken');

  const buyerToken = loginOtp('9000000101', 'BUYER');
  if (!buyerToken) throw new Error('Buyer login failed');

  return { adminToken, buyerToken };
}

export default function (data) {
  const { buyerToken, adminToken } = data;
  const now = Date.now();

  // ── Batch preview (read-only, safe) ──────────────────────────
  const previewPayload = {
    items: [
      { title: `k6-preview-task-${now}`, description: 'k6 load test preview item validation',
        urgency: 'NORMAL', timeMinutes: 60, budgetPaise: 50000,
        lat: 17.3850, lng: 78.4867 },
    ]
  };
  const previewRes = http.post(`${API_BASE}/api/v1/batches/preview`,
    JSON.stringify(previewPayload), { headers: authHeaders(buyerToken), tags: { step: 'batch_preview' } });
  batchPreviewTrend.add(previewRes.timings.duration);
  const previewOk = check(previewRes, {
    'preview 200': r => r.status === 200,
    'preview has items': r => Array.isArray(r.json('items')),
  });
  if (!previewOk) failures.add(1);

  // ── Batch create (3 items, direct route) ──────────────────────
  const idemKey = `k6-${now}-${Math.random().toString(36).substring(2, 8)}`;
  const createPayload = {
    title: `k6-batch-${now}`,
    idempotencyKey: idemKey,
    items: [
      { title: `k6-item-1-${now}`, description: 'k6 load test batch item first task',
        urgency: 'NORMAL', timeMinutes: 30, budgetPaise: 30000, lat: 17.3850, lng: 78.4867 },
      { title: `k6-item-2-${now}`, description: 'k6 load test batch item second task',
        urgency: 'HIGH', timeMinutes: 45, budgetPaise: 45000, lat: 17.3850, lng: 78.4867 },
    ]
  };
  const createRes = http.post(`${API_BASE}/api/v1/batches`,
    JSON.stringify(createPayload), { headers: authHeaders(buyerToken), tags: { step: 'batch_create' } });
  batchCreateTrend.add(createRes.timings.duration);
  const createOk = check(createRes, {
    'batch create 200': r => r.status === 200,
    'batch has batchId': r => !!r.json('batchId'),
  });
  if (!createOk) failures.add(1);

  const batchId = createRes.status === 200 ? createRes.json('batchId') : null;

  // ── Batch summary (read) ──────────────────────────────────────
  if (batchId) {
    const summaryRes = http.get(`${API_BASE}/api/v1/batches/${batchId}`,
      { headers: authHeaders(buyerToken), tags: { step: 'batch_summary' } });
    batchSummaryTrend.add(summaryRes.timings.duration);
    check(summaryRes, { 'summary 200': r => r.status === 200 });
  }

  // ── Schedule later task ───────────────────────────────────────
  const scheduledAt = new Date(Date.now() + 15 * 60 * 1000).toISOString();
  const schedPayload = {
    title: `k6-scheduled-${now}`,
    description: 'k6 load test for scheduled task creation',
    urgency: 'NORMAL', timeMinutes: 60, budgetPaise: 60000,
    lat: 17.3850, lng: 78.4867,
    scheduledAt,
  };
  const schedRes = http.post(`${API_BASE}/api/v1/tasks`,
    JSON.stringify(schedPayload), { headers: authHeaders(buyerToken), tags: { step: 'schedule_task' } });
  scheduleTaskTrend.add(schedRes.timings.duration);
  const schedOk = check(schedRes, {
    'schedule task 200': r => r.status === 200,
    'schedule has taskId': r => !!r.json('taskId'),
  });
  if (!schedOk) failures.add(1);

  // Cancel scheduled task to keep prod clean
  const schedTaskId = schedRes.status === 200 ? schedRes.json('taskId') : null;
  if (schedTaskId) {
    http.post(`${API_BASE}/api/v1/tasks/${schedTaskId}/cancel`,
      JSON.stringify({ reason: 'k6 load test cleanup' }),
      { headers: authHeaders(buyerToken) });
  }

  sleep(1);
}
