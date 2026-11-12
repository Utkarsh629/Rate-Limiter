// Phase 3B scenario B4 — Headroom vs throttled: 5,000 RPS × TB, FW, SW through Nginx, once with the headroom
// profile (10,000 users far below 120/min: allowed path) and once with the throttled profile (100 users, 50×
// their allowance: rejected path). PROFILE selects the side.
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B4', { profiles: ['headroom', 'throttled'] });
const tokens = loadTokens(cfg);
export const options = buildOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
