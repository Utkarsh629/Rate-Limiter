// Phase 3B scenario B8 — Hot key: 2,000 and 5,000 RPS × TB, FW, SW, every request from ONE user (a fresh
// HOT_TOKEN per run when given), through Nginx: single-key Redis behaviour.
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B8', { profiles: ['hotkey'] });
const tokens = loadTokens(cfg);
export const options = buildOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
