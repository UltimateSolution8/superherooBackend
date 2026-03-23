import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const API_BASE = __ENV.API_BASE || 'https://api.mysuperhero.xyz';
const REALTIME_WS = __ENV.REALTIME_WS || 'wss://superheroorealtime.onrender.com/socket.io/?EIO=4&transport=websocket';

const BUYER_EMAIL = __ENV.BUYER_EMAIL || 'buyer1@helpinminutes.app';
const BUYER_PASSWORD = __ENV.BUYER_PASSWORD || 'Buyer@12345';
const HELPER_EMAIL = __ENV.HELPER_EMAIL || 'helper.approved@helpinminutes.app';
const HELPER_PASSWORD = __ENV.HELPER_PASSWORD || 'Helper@12345';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@helpinminutes.app';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'Admin@12345';

const failures = new Counter('flow_failures');
const createTaskTrend = new Trend('create_task_ms');
const helperAvailableTrend = new Trend('helper_available_ms');
const adminSummaryTrend = new Trend('admin_summary_ms');

export const options = {
  scenarios: {
    buyerHelperAdminFlow: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 5 },
        { duration: '1m', target: 10 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500'],
    flow_failures: ['count<20'],
  },
};

function login(email, password) {
  const res = http.post(
    `${API_BASE}/api/v1/auth/password/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const ok = check(res, {
    'login status is 200': (r) => r.status === 200,
    'login token exists': (r) => !!(r.json('accessToken')),
  });
  if (!ok) {
    failures.add(1);
    return null;
  }
  return res.json('accessToken');
}

function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}

export function setup() {
  const buyerToken = login(BUYER_EMAIL, BUYER_PASSWORD);
  const helperToken = login(HELPER_EMAIL, HELPER_PASSWORD);
  const adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (!buyerToken || !helperToken || !adminToken) {
    throw new Error('Failed to acquire setup tokens');
  }
  return { buyerToken, helperToken, adminToken };
}

export default function (data) {
  const now = Date.now();
  const createPayload = {
    title: `k6-task-${now}`,
    description: 'k6 synthetic flow',
    urgency: 'NORMAL',
    timeMinutes: 20,
    budgetPaise: 20000,
    lat: 12.9352,
    lng: 77.6245,
    addressText: 'k6 Bangalore point',
  };

  const createRes = http.post(
    `${API_BASE}/api/v1/tasks`,
    JSON.stringify(createPayload),
    { headers: authHeaders(data.buyerToken), tags: { step: 'buyer_create_task' } },
  );
  createTaskTrend.add(createRes.timings.duration);
  const createOk = check(createRes, {
    'create status 200': (r) => r.status === 200,
    'create has taskId': (r) => !!r.json('taskId'),
  });
  if (!createOk) failures.add(1);

  const taskId = createRes.status === 200 ? createRes.json('taskId') : null;

  // Helper polls available tasks
  const availableRes = http.get(
    `${API_BASE}/api/v1/tasks/available`,
    { headers: { Authorization: `Bearer ${data.helperToken}` }, tags: { step: 'helper_available' } },
  );
  helperAvailableTrend.add(availableRes.timings.duration);
  const availableOk = check(availableRes, { 'available status 200': (r) => r.status === 200 });
  if (!availableOk) failures.add(1);

  // Optional accept if the created task is visible to helper.
  if (taskId && availableRes.status === 200) {
    const ids = (availableRes.json() || []).map((t) => t.id);
    if (ids.includes(taskId)) {
      const acceptRes = http.post(
        `${API_BASE}/api/v1/tasks/${taskId}/accept`,
        null,
        { headers: { Authorization: `Bearer ${data.helperToken}` }, tags: { step: 'helper_accept' } },
      );
      check(acceptRes, { 'accept status 200|409': (r) => r.status === 200 || r.status === 409 });
    }
  }

  // Admin read
  const adminRes = http.get(
    `${API_BASE}/api/v1/admin/summary`,
    { headers: { Authorization: `Bearer ${data.adminToken}` }, tags: { step: 'admin_summary' } },
  );
  adminSummaryTrend.add(adminRes.timings.duration);
  const adminOk = check(adminRes, { 'admin summary 200': (r) => r.status === 200 });
  if (!adminOk) failures.add(1);

  // Realtime websocket connect smoke (Socket.IO handshake channel).
  const wsRes = ws.connect(REALTIME_WS, {}, function (socket) {
    socket.on('open', function () {
      socket.send('2probe');
    });
    socket.on('message', function (msg) {
      if (msg === '3probe') {
        socket.send('5');
      }
    });
    socket.setTimeout(function () {
      socket.close();
    }, 1000);
  });
  check(wsRes, { 'ws connect status 101': (r) => r && r.status === 101 });

  sleep(1);
}
