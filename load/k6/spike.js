// Spike: steady base load, a 10x burst for 60 s, then base load again to watch lag recover.
// Env: BASE_RATE=50 WARM_SECONDS=30 BURST_SECONDS=60 RECOVER_SECONDS=120
import { loadIteration, scenarioThresholds, trendStats, summarise, waitForGateway } from './lib.js';

const base = Number(__ENV.BASE_RATE || 50);
const warm = Number(__ENV.WARM_SECONDS || 30);
const burst = Number(__ENV.BURST_SECONDS || 60);
const recover = Number(__ENV.RECOVER_SECONDS || 120);
const maxVUs = Number(__ENV.MAX_VUS || 2000);

const phase = (rate, seconds, start) => ({
  executor: 'constant-arrival-rate',
  rate,
  timeUnit: '1s',
  duration: `${seconds}s`,
  startTime: `${start}s`,
  preAllocatedVUs: Math.max(50, Math.ceil(rate / 4)),
  maxVUs,
  exec: 'load',
});

export const options = {
  scenarios: {
    warm: phase(base, warm, 0),
    burst: phase(base * 10, burst, warm),
    recover: phase(base, recover, warm + burst),
  },
  summaryTrendStats: trendStats,
  thresholds: Object.assign({ http_req_failed: ['rate<0.001'] },
    scenarioThresholds(['warm', 'burst', 'recover'])),
};

export function setup() { waitForGateway(); }
export function load() { loadIteration(); }
export function handleSummary(data) { return summarise(data); }
