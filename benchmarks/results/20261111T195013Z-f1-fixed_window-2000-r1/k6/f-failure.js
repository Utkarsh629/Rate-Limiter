// Phase 3B failure campaigns F1–F5: constant 2,000 RPS, 90 % to the fail-open benchmark endpoint
// (/api/v1/demo/profile, FREE users, bench-profile) and 10 % to the fail-closed endpoint (/api/v1/demo/enterprise,
// ENTERPRISE users, seeded policy demo-enterprise-endpoint, FAIL_CLOSED), through Nginx. The fault itself is
// injected by run.sh during the measure phase; this script only generates the load and tags every request with
// phase and endpoint, so the per-second timeline comes from the Nginx access log.
import { loadConfig, loadTokens, loadGroupTokens, buildFailureOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';
import exec from 'k6/execution';

const cfg = loadConfig(__ENV.FAULT || 'F', { profiles: ['mixed'] });
const tokens = loadTokens(cfg);
const entTokens = loadGroupTokens(cfg, __ENV.ENT_TOKENS_FILE || '/tokens/bench-enterprise.json', 'enterprise', 'ENTERPRISE');
export const options = buildFailureOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens, { tags: { endpoint: 'profile' } }); }
export function enterprise() {
  const k = exec.scenario.iterationInTest % entTokens.length;
  sendRequest(cfg, tokens, {
    path: '/api/v1/demo/enterprise', tags: { endpoint: 'enterprise' },
    user: { group: 'enterprise', index: `e${k}`, token: entTokens[k] },
  });
}
export function handleSummary(data) { return summaryOutputs(cfg, data); }
