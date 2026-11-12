// Phase 3B scenario B0 — Baseline: 100 RPS × TB, FW, SW through Nginx (floor latency). Mixed profile, so the
// request mix matches B1; at 100 RPS no user reaches the 120/min allowance. Same environment as b1-load-levels.js.
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B0', { profiles: ['mixed'] });
const tokens = loadTokens(cfg);
export const options = buildOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
