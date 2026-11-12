// Phase 3B scenario B2 — Stress / knee: warm-up at START_RATE (1,000), then a stepped ramp of STEP_RATE (1,000)
// every STEP_S (30 s) up to RATE (15,000), mixed profile, through Nginx. Every request is tagged step=<target rate>
// so throughput, latency and errors are reported per step; the knee and the maximum sustainable throughput are
// derived from the per-step data and confirmed with constant-rate runs. 15,000 RPS is a TARGET only.
import { loadConfig, loadTokens, buildRampOptions, sendRequest, summaryOutputs, benchSetup, currentStep, currentPhase } from './lib/common.js';

const cfg = loadConfig('B2', { profiles: ['mixed'] });
const tokens = loadTokens(cfg);
export const options = buildRampOptions(cfg);
export function setup() { return benchSetup(); }
export default function (data) {
  const step = currentPhase() === 'measure' ? currentStep(cfg, data) : 'warmup';
  sendRequest(cfg, tokens, { tags: { step } });
}
export function handleSummary(data) { return summaryOutputs(cfg, data); }
