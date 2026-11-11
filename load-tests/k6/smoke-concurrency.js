// 3A smoke (AC14): 1,000 simultaneous requests through Nginx from ONE identity against a deterministic
// policy (token bucket, capacity 100, refill 1 per day). Exactly 100 must be accepted and 900 rejected.
// Closed model on purpose: all 1,000 virtual users fire once, together.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const accepted = new Counter('accepted');
const rejected = new Counter('rejected');
const unexpected = new Counter('unexpected');
const BASE = __ENV.BASE_URL || 'http://nginx:8080';

export const options = {
  scenarios: { together: { executor: 'per-vu-iterations', vus: 1000, iterations: 1, maxDuration: '60s' } },
  thresholds: { accepted: ['count==100'], rejected: ['count==900'], unexpected: ['count==0'] },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const r = http.get(`${BASE}/api/v1/demo/profile`, { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } });
  if (r.status === 200) accepted.add(1);
  else if (r.status === 429) rejected.add(1);
  else unexpected.add(1);
}

export function handleSummary(data) {
  const v = (m) => (data.metrics[m] ? data.metrics[m].values.count : 0);
  const line = `accepted=${v('accepted')} rejected=${v('rejected')} unexpected=${v('unexpected')} requests=${v('http_reqs')}\n`;
  return { [`/out/${__ENV.RUN_ID}/smoke-concurrency-summary.json`]: JSON.stringify(data, null, 2), stdout: line };
}
