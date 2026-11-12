// Phase 3B scenario B5 — Burst: after a warm-up at 1,000 RPS (mixed users), 10,000 requests in 1 s from ONE
// fresh user (HOT_TOKEN, minted per run), × TB, FW, SW through Nginx. Accepted count is compared with the
// algorithm's bound for a fresh user (token bucket 120 + ⌊2·D⌋ + 1; windows 120 × windows touched).
import { loadConfig, loadTokens, buildBurstOptions, sendRequest, summaryOutputs, benchSetup, currentPhase, chooseUser } from './lib/common.js';
import exec from 'k6/execution';

const cfg = loadConfig('B5', { profiles: ['hotkey'] });
const tokens = loadTokens({ ...cfg, profile: 'mixed' });   // warm-up users
if (!cfg.hotToken) throw new Error('configuration error: HOT_TOKEN is required for B5 (fresh burst user)');
export const options = buildBurstOptions(cfg);
export function setup() { return benchSetup(); }
export default function () {
  if (currentPhase() === 'warmup') {
    sendRequest(cfg, tokens, { user: chooseUser({ ...cfg, profile: 'mixed' }, tokens, exec.scenario.iterationInTest) });
  } else {
    sendRequest(cfg, tokens);
  }
}
export function handleSummary(data) { return summaryOutputs(cfg, data); }
