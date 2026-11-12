// Phase 3B scenario B1 — Load levels: 1,000 / 2,500 / 5,000 / 7,500 / 10,000 RPS × TB, FW, SW,
// mixed profile, through Nginx. One k6 run = one (rate, algorithm, repetition). 10,000 RPS is a TARGET only.
//
// Run phases: warm-up WARMUP_S (default 60 s, tag phase=warmup), then measure MEASURE_S (default
// 180 s, tag phase=measure). Thresholds and reported latency use phase=measure only; request counts are
// reported per phase and in total (exact reconciliation covers the whole run, decision O2).
// Redis reset, the JIT warm-up before a session's first run, snapshots and collection belong to run.sh.
//
// Environment (validated at init; a bad value aborts before any request is sent):
//   RUN_ID     required  output directory below OUT_DIR, e.g. 20261112-b1-token_bucket-1000-r1
//   RATE       required  target arrival rate, requests/s
//   ALGORITHM  required  token_bucket | fixed_window | sliding_window   (a label: setup-policies.sh sets the policy)
//   PROFILE    mixed     B1 is defined with the mixed profile only; any other value is rejected
//   WARMUP_S 60 · MEASURE_S 180 · LATENCY_MS 10 (VU sizing estimate) · PRE_VUS / MAX_VUS (override sizing)
//   BASE_URL http://nginx:8080 · TOKENS_FILE /tokens/bench.json · OUT_DIR /results
//
// Tokens: python3 scripts/bench/mint-tokens.py --free 10000 --hot 100 --ttl <seconds> \
//           --out load-tests/k6/.tokens/bench.json        ("free" = headroom users, "hot" = throttled users)
//
// Benchmark policy (scripts/bench/setup-policies.sh, decision O5): 120 requests per 60 s per user, same
// allowance for every algorithm:
//   token_bucket   capacity 120, refill 120 per 60 s (2/s continuous): a full bucket allows 120 at once,
//                  then 2/s; per-user bound over D s: 120 + ⌊2·D⌋ + 1
//   fixed_window   120 per aligned 60 s window (Redis TIME): up to 240 within a short span across a boundary;
//                  bound 120 × windows touched
//   sliding_window 120 per 60 s, previous window weighted by overlap; current-window count never exceeds 120;
//                  bound 120 × windows touched (approximation documented in docs/05)
//
// Mixed profile: 80 % of requests round-robin over 10,000 headroom users, 20 % over 100 throttled users.
// With a FIXED allowance (120/min), the throttling ratio changes with the arrival rate:
//
//   RATE (req/s) | headroom user req/min (× allowance) | throttled user req/min (× allowance)
//   1,000        |  4.8 (0.04×)                        |   120 (1.0× — at the allowance, barely throttled)
//   2,500        | 12   (0.10×)                        |   300 (2.5×)
//   5,000        | 24   (0.20×)                        |   600 (5.0× — the plan's "~5×")
//   7,500        | 36   (0.30×)                        |   900 (7.5×)
//   10,000       | 48   (0.40×)                        | 1,200 (10×)
//
// So the share of 429 responses, and with it the allowed/rejected path mix, differs between load levels and
// between algorithms; results are compared per (rate, algorithm), never across rates as if the mix were equal.
import { loadConfig, loadTokens, buildOptions, sendRequest, summaryOutputs, benchSetup } from './lib/common.js';

const cfg = loadConfig('B1', { profiles: ['mixed'] }); // B1 = mixed profile (approved scenario definition)
const tokens = loadTokens(cfg);

export const options = buildOptions(cfg);

export function setup() {
  return benchSetup();
}

export default function () {
  sendRequest(cfg, tokens);
}

export function handleSummary(data) {
  return summaryOutputs(cfg, data);
}
