// 3A smoke (AC18): a FUNCTIONAL load check, not a benchmark. 60 s at a fixed arrival rate of 1,000
// requests/s through Nginx, so that every dashboard panel has data and the request counts seen by k6,
// Nginx, Prometheus and /stats can be reconciled. Open model: arrivals do not slow down with the server.
//   95 % of requests: 2,000 FREE users far below their limit (allowed path)
//    5 % of requests: 10 "hot" FREE users far above their limit (rejected path)
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const tokens = JSON.parse(open('/tokens/smoke-load.json'));
const free = new SharedArray('free', () => tokens.users.filter((u) => u.group === 'free').map((u) => u.token));
const hot = new SharedArray('hot', () => tokens.users.filter((u) => u.group === 'hot').map((u) => u.token));

const accepted = new Counter('accepted');
const rejected = new Counter('rejected');
const unexpected = new Counter('unexpected');
const BASE = __ENV.BASE_URL || 'http://nginx:8080';
const RATE = Number(__ENV.RATE || 1000);

export const options = {
  scenarios: {
    load: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '60s',
            preAllocatedVUs: 100, maxVUs: 500 },
  },
  thresholds: { dropped_iterations: ['count==0'], unexpected: ['count==0'] },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const token = i % 20 === 0 ? hot[(i / 20) % hot.length] : free[i % free.length];
  const r = http.get(`${BASE}/api/v1/demo/public`, { headers: { Authorization: `Bearer ${token}` } });
  if (r.status === 200) accepted.add(1);
  else if (r.status === 429) rejected.add(1);
  else unexpected.add(1);
}

export function handleSummary(data) {
  const v = (m) => (data.metrics[m] ? data.metrics[m].values.count : 0);
  const line = `accepted=${v('accepted')} rejected=${v('rejected')} unexpected=${v('unexpected')} `
    + `requests=${v('http_reqs')} dropped_iterations=${v('dropped_iterations')}\n`;
  return { [`/out/${__ENV.RUN_ID}/smoke-load-summary.json`]: JSON.stringify(data, null, 2), stdout: line };
}
