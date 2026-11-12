// Phase 3B scenario B6 — Concurrency: 1,000 virtual users send one request each, together, from ONE fresh user
// (HOT_TOKEN) against the concurrency policy (token bucket, capacity 100, refill 1 per day), through Nginx to all
// 3 instances. Exactly 100 must be accepted and 900 rejected. Closed model on purpose; no warm-up.
import { loadConfig, loadTokens, buildConcurrencyOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B6', { profiles: ['hotkey'] });
const tokens = loadTokens(cfg);
if (!cfg.hotToken) throw new Error('configuration error: HOT_TOKEN is required for B6 (fresh user)');
export const options = buildConcurrencyOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
