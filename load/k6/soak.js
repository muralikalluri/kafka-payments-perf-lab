// Soak: a steady arrival rate for a long time, to expose memory growth, connection exhaustion and slow degradation.
// SPEC: 60% of the profile's maximum sustainable rate for 60 minutes. run-benchmark.sh derives SOAK_RATE from the
// profile's own steady result (SOAK_RATE overrides) and passes SOAK_MINUTES (default 60).
import { loadIteration, scenarioThresholds, trendStats, summarise, waitForGateway } from './lib.js';

const rate = Number(__ENV.SOAK_RATE);
const minutes = Number(__ENV.SOAK_MINUTES || 60);
if (!rate) {
  throw new Error('SOAK_RATE is required (run-benchmark.sh derives it from the profile\'s steady result)');
}

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: `${minutes}m`,
      preAllocatedVUs: Math.max(50, Math.ceil(rate / 4)),
      maxVUs: Number(__ENV.MAX_VUS || 2000),
      exec: 'load',
    },
  },
  summaryTrendStats: trendStats,
  thresholds: Object.assign({ http_req_failed: ['rate<0.001'] }, scenarioThresholds(['soak'])),
};

export function setup() { waitForGateway(); }
export function load() { loadIteration(); }
export function handleSummary(data) { return summarise(data); }
