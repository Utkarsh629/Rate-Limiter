// Phase 3B scenario B3 — Limiter overhead A/B: 1,000 and 5,000 RPS, token bucket, mixed profile, through Nginx.
// The same script runs against the normal stack and against the stack variant "limiter-off"
// (ratelimiter.filter.enabled=false, applied by run.sh); the end-to-end difference is compared with the
// in-process overhead histogram. JWT validation stays on in both variants.
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B3', { profiles: ['mixed'] });
const tokens = loadTokens(cfg);
export const options = buildOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
