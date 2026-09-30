// Smoke: 10 VUs for 1 minute. Correctness check, not a benchmark.
// Payload mix (SPEC §6): 80% normal, 10% duplicate idempotency key, 10% failing validation.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.GATEWAY_URL || 'http://localhost:8080';
const CLIENT = 'client-demo';
const wrongFinalStatus = new Counter('wrong_final_status');

export const options = {
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 10, duration: '1m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    wrong_final_status: ['count==0'],
    checks: ['rate>0.999'],
  },
};

const rnd = (lo, hi) => lo + Math.floor(Math.random() * (hi - lo + 1));
const acc = (n) => `ACC-${String(n).padStart(4, '0')}`;
const headers = (key) => ({
  'Content-Type': 'application/json',
  'Idempotency-Key': key,
  'X-Client-Id': CLIENT,
});

export function setup() {
  // Wait for the gateway so a cold start is not counted as failures.
  for (let i = 0; i < 60; i++) {
    const r = http.get(`${BASE}/actuator/health`);
    if (r.status === 200) return;
    sleep(1);
  }
  throw new Error('gateway did not become healthy');
}

let last = null; // per-VU: previous request, replayed for the duplicate share

function awaitStatus(paymentId) {
  for (let i = 0; i < 20; i++) {
    const r = http.get(`${BASE}/payments/${paymentId}`, { headers: { 'X-Client-Id': CLIENT } });
    if (r.status === 200 && r.json('status') !== 'ACCEPTED') return r.json();
    sleep(0.25);
  }
  return null;
}

export default function () {
  const roll = Math.random();
  let key, body, expected;

  if (roll < 0.1 && last) {
    ({ key, body } = last); // duplicate: same key, same body
    const res = http.post(`${BASE}/payments`, body, { headers: headers(key) });
    check(res, {
      'duplicate returns 202': (r) => r.status === 202,
      'duplicate returns original paymentId': (r) => r.json('paymentId') === last.paymentId,
    });
    return;
  }

  key = `smoke-${__VU}-${__ITER}-${Date.now()}`;
  const failing = roll >= 0.9;
  body = JSON.stringify({
    debtorAccountId: acc(rnd(1, 800)),
    creditorAccountId: failing ? 'SANC-0001' : acc(rnd(801, 990)),
    merchantId: `MER-${rnd(1, 20)}`,
    amountMinor: rnd(1, 100),
    currency: 'USD',
  });
  expected = failing ? 'REJECTED' : 'POSTED';

  const res = http.post(`${BASE}/payments`, body, { headers: headers(key) });
  const ok = check(res, { 'accepted with 202': (r) => r.status === 202 });
  if (!ok) return;
  const paymentId = res.json('paymentId');
  last = { key, body, paymentId };

  const final = awaitStatus(paymentId);
  const good = check(final, {
    'reached a terminal status': (f) => f !== null,
    'terminal status is the expected one': (f) => f !== null && f.status === expected,
  });
  if (!good) wrongFinalStatus.add(1);
  sleep(0.1);
}
