// Shared helpers for the k6 scenarios.
// Payload mix (SPEC §6): 80% normal, 10% duplicate idempotency key, 10% failing validation.
// Merchant skew: half of all traffic belongs to one large merchant (MER-001). Under the baseline
// merchant-id record key (F-12) that concentrates load on one partition.
import http from 'k6/http';
import { check, sleep } from 'k6';

export const BASE = __ENV.GATEWAY_URL || 'http://localhost:8080';
export const CLIENT = 'client-demo';

const rnd = (lo, hi) => lo + Math.floor(Math.random() * (hi - lo + 1));
const acc = (n) => `ACC-${String(n).padStart(4, '0')}`;
const merchant = () => (Math.random() < 0.5 ? 'MER-001' : `MER-${String(rnd(2, 20)).padStart(3, '0')}`);

let last = null; // per-VU: previous request, replayed for the duplicate share

export function headers(key) {
  return { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-Client-Id': CLIENT };
}

export function newPayment(failing) {
  return JSON.stringify({
    debtorAccountId: acc(rnd(1, 800)),
    creditorAccountId: failing ? 'SANC-0001' : acc(rnd(801, 990)),
    merchantId: merchant(),
    amountMinor: rnd(1, 100),
    currency: 'USD',
  });
}

/** One load iteration: POST only (asynchronous pipeline; end-to-end latency is measured from the DB). */
export function loadIteration() {
  const roll = Math.random();
  if (roll < 0.1 && last) {
    const res = http.post(`${BASE}/payments`, last.body, { headers: headers(last.key) });
    check(res, { 'duplicate accepted': (r) => r.status === 202 });
    return;
  }
  const key = `load-${__VU}-${__ITER}-${Date.now()}`;
  const body = newPayment(roll >= 0.9);
  const res = http.post(`${BASE}/payments`, body, { headers: headers(key) });
  if (check(res, { 'accepted with 202': (r) => r.status === 202 })) {
    last = { key, body };
  }
}

export function waitForGateway() {
  for (let i = 0; i < 60; i++) {
    if (http.get(`${BASE}/actuator/health`).status === 200) return;
    sleep(1);
  }
  throw new Error('gateway did not become healthy');
}

export const trendStats = ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

/** Writes the raw k6 summary for run-benchmark.sh and prints a compact threshold report. */
export function summarise(data) {
  const lines = [];
  Object.keys(data.metrics).forEach((name) => {
    const t = data.metrics[name].thresholds;
    if (t) Object.keys(t).forEach((expr) => lines.push(`${t[expr].ok ? 'PASS' : 'FAIL'}  ${name}  ${expr}`));
  });
  const out = { stdout: lines.join('\n') + '\n' };
  if (__ENV.SUMMARY_PATH) out[__ENV.SUMMARY_PATH] = JSON.stringify(data, null, 2);
  return out;
}

/** Per-scenario submetrics only exist if a threshold names them; these are informational. */
export function scenarioThresholds(names) {
  const t = {};
  names.forEach((n) => {
    t[`http_req_duration{scenario:${n}}`] = ['p(99)<500'];
    t[`http_req_failed{scenario:${n}}`] = ['rate<0.001'];
    t[`http_reqs{scenario:${n}}`] = ['count>=0'];
    t[`dropped_iterations{scenario:${n}}`] = ['count>=0'];
  });
  return t;
}
