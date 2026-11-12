// Phase 3B scenario B9 — Redis connection pooling: 1,000 / 5,000 / knee RPS, token bucket, mixed profile, through
// Nginx, against the stack variants pool-off (shared connection, default) and pool-16 (applied by run.sh; 8 and
// 32 only if 16 helps).
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B9', { profiles: ['mixed'] });
const tokens = loadTokens(cfg);
export const options = buildOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
