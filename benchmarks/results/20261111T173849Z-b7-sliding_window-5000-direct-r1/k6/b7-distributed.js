// Phase 3B scenario B7 — Distributed: 5,000 RPS × TB, FW, SW, mixed profile; through Nginx (default) or direct
// to the three instances (BASE_URLS=http://app1:8080,http://app2:8080,http://app3:8080, round-robin), to compare
// the per-instance load split and the cost of the proxy hop.
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B7', { profiles: ['mixed'] });
const tokens = loadTokens(cfg);
export const options = buildOptions(cfg);
export function setup() { return benchSetup(); }
export default function () { sendRequest(cfg, tokens); }
export function handleSummary(data) { return summaryOutputs(cfg, data); }
