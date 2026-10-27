// k6 load for the RFC 0008 §3.8 end-to-end budget: a constant arrival rate of anonymous GETs through the gateway.
// Usage: k6 run -e RATE=10000 -e DURATION=60s -e TARGET=http://127.0.0.1:18080/bench/ok load.js
import http from 'k6/http';
import { check } from 'k6';

const rate = Number(__ENV.RATE || 1000);

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'p(99.9)', 'max'],
  scenarios: {
    constant: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: Number(__ENV.VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 2000),
    },
  },
};

const target = __ENV.TARGET || 'http://127.0.0.1:18080/bench/ok';

export default function () {
  const res = http.get(target);
  check(res, { 'status is 200': (r) => r.status === 200 });
}

// Writes the full summary as JSON (SUMMARY) and one line for the log; run-e2e.sh reads the JSON.
export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, key) => (m[name] ? m[name].values[key] : 0);
  const line = `rate=${rate} achieved=${v('iterations', 'rate').toFixed(1)} dropped=${v('dropped_iterations', 'count')} ` +
    `failed=${v('http_req_failed', 'rate')} p50=${v('http_req_duration', 'med').toFixed(2)}ms ` +
    `p99=${v('http_req_duration', 'p(99)').toFixed(2)}ms p99.9=${v('http_req_duration', 'p(99.9)').toFixed(2)}ms\n`;
  const out = { stdout: line };
  if (__ENV.SUMMARY) out[__ENV.SUMMARY] = JSON.stringify(data, null, 2);
  return out;
}
