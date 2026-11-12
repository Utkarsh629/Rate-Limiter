// Phase 3B scenario B10 — Correctness under load: 2,000 RPS × TB, FW, SW, throttled profile (100 users, each
// 1,200 requests/min = 10× the 120/min allowance), through Nginx. The User-Agent carries the user index
// (UA_USER=1) so per-user accepted counts are taken from the Nginx access log and checked against the bounds:
// token bucket accepted ≤ 120 + ⌊2·D⌋ + 1; fixed and sliding window accepted ≤ 120 × windows touched.
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B10', { profiles: ['throttled'] });
if (!cfg.uaUser) throw new Error('configuration error: B10 requires UA_USER=1 (per-user accounting)');
const tokens = loadTokens(cfg);
export const options = buildOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
