// Steady: stepped constant arrival rate. Finds the max sustainable throughput at the p99 SLO.
// SPEC default is 200 -> 500 -> 1000 -> 2000 req/s, 5 min each; adjust to hardware via env:
//   STEPS="25,50,100,200,400,800" STEP_SECONDS=60
import { loadIteration, scenarioThresholds, trendStats, summarise, waitForGateway } from './lib.js';

const steps = (__ENV.STEPS || '25,50,100,200,400,800').split(',').map(Number);
const stepSeconds = Number(__ENV.STEP_SECONDS || 60);

const scenarios = {};
const names = [];
steps.forEach((rate, i) => {
  const name = `step_${rate}`;
  names.push(name);
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: `${stepSeconds}s`,
    startTime: `${i * stepSeconds}s`,
    preAllocatedVUs: Math.max(50, Math.ceil(rate / 4)),
    maxVUs: Number(__ENV.MAX_VUS || 2000),
    exec: 'load',
  };
});

export const options = {
  scenarios,
  summaryTrendStats: trendStats,
  thresholds: Object.assign({ http_req_failed: ['rate<0.001'] }, scenarioThresholds(names)),
};

export function setup() { waitForGateway(); }
export function load() { loadIteration(); }
export function handleSummary(data) { return summarise(data); }
