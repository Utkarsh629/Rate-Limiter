// Phase 3B benchmark infrastructure: shared k6 library for the benchmark scenarios (decisions O2–O5).
//
// Provides: validated configuration from __ENV, token loading (tokens minted offline by
// scripts/bench/mint-tokens.py), request mixes, warm-up + measure scenarios with phase tags,
// accepted/rejected/unexpected counters, validity thresholds restricted to the measure phase, and a
// standard handleSummary that writes into the run directory.
//
// Nothing here decides whether a run is valid on its own: thresholds make k6 fail fast and expose the
// measure-phase sub-metrics in the summary; run.sh and the validity checks apply the invalid-run rules.
import http from 'k6/http';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';

export const ENDPOINT = '/api/v1/demo/profile'; // the dedicated bench-profile policy (setup-policies.sh)
export const ALGORITHMS = ['token_bucket', 'fixed_window', 'sliding_window']; // Prometheus label values
export const PROFILES = ['mixed', 'headroom', 'throttled', 'hotkey'];          // request mixes (anonymous: later)
export const PHASES = ['warmup', 'measure'];

// ---------------------------------------------------------------------------------------------------
// Configuration: every value comes from __ENV, is validated in the init context, and fails loudly.

function fail(msg) {
  throw new Error(`configuration error: ${msg}`);
}

function intEnv(name, dflt, min, max) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    if (dflt === undefined) fail(`${name} is required`);
    return dflt;
  }
  if (!/^[0-9]+$/.test(raw)) fail(`${name} must be a whole number, got '${raw}'`);
  const v = Number(raw);
  if (v < min || v > max) fail(`${name} must be in [${min}, ${max}], got ${v}`);
  return v;
}

function oneOf(name, allowed, dflt) {
  const v = __ENV[name] === undefined || __ENV[name] === '' ? dflt : __ENV[name];
  if (v === undefined) fail(`${name} is required (one of ${allowed.join(', ')})`);
  if (!allowed.includes(v)) fail(`${name} must be one of ${allowed.join(', ')}, got '${v}'`);
  return v;
}

const ALLOWED_BASE_URLS = ['http://nginx:8080', 'http://app1:8080', 'http://app2:8080', 'http://app3:8080'];

function parseBaseUrls(raw) {
  if (!raw) return [];
  const urls = raw.split(',').map((s) => s.trim()).filter((s) => s);
  for (const u of urls) if (!ALLOWED_BASE_URLS.includes(u)) fail(`BASE_URLS entry not allowed: '${u}'`);
  return urls;
}

// Durations are given in whole seconds (WARMUP_S, MEASURE_S) so that phase boundaries are exact.
// `profiles` restricts PROFILE for scenarios defined with specific request mixes (e.g. B1: ['mixed']);
// the first entry is the default. The library itself supports every profile in PROFILES.
export function loadConfig(benchmark, { profiles = PROFILES } = {}) {
  if (!profiles.length || profiles.some((p) => !PROFILES.includes(p))) fail(`invalid profile restriction for ${benchmark}`);
  const runId = __ENV.RUN_ID;
  if (!runId) fail('RUN_ID is required (it names the output directory)');
  // Relative path below OUT_DIR: safe characters only, no empty, "." or ".." segment (cannot escape OUT_DIR).
  if (!/^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/.test(runId) || runId.split('/').some((s) => s === '.' || s === '..')) {
    fail(`RUN_ID must be a relative path of [A-Za-z0-9._-] segments without "." or "..": '${runId}'`);
  }
  const rate = intEnv('RATE', undefined, 1, 50000);
  const warmupS = intEnv('WARMUP_S', 60, 0, 3600);   // approved run phases: 60 s warm-up
  const measureS = intEnv('MEASURE_S', 180, 1, 3600); // approved run phases: 180 s measurement
  // VU sizing: VUs ≈ rate × latency (plan). LATENCY_MS is a planning value for the latency TAIL, not a measurement:
  // the 1,000 RPS validation runs showed stalls of 65–117 ms (E2E max), and with fewer pre-allocated VUs than
  // rate × stall k6 had to create VUs mid-run and dropped iterations. 200 ms covers twice the 100 ms E2E P99
  // criterion. Measured cost: k6 used ~150 MiB with 100 or 154 VUs (the token array dominates), so pre-allocation is
  // cheap; it is capped at 4,000. Runs that still drop iterations are recorded INVALID, never hidden.
  const latencyMs = intEnv('LATENCY_MS', 200, 1, 10000);
  const estimate = Math.ceil((rate * latencyMs) / 1000);
  const preVUs = intEnv('PRE_VUS', Math.min(4000, Math.max(200, estimate)), 1, 20000);
  const maxVUs = intEnv('MAX_VUS', Math.min(5000, Math.ceil(preVUs * 1.25)), preVUs, 20000);
  const warmPreVUs = intEnv('WARMUP_PRE_VUS', Math.max(100, Math.ceil(preVUs / 2)), 1, 20000);
  return {
    benchmark,
    runId,
    rate,
    warmupS,
    measureS,
    latencyMs,
    preVUs,
    maxVUs,
    warmPreVUs,
    algorithm: oneOf('ALGORITHM', ALGORITHMS),
    profile: oneOf('PROFILE', profiles, profiles[0]),
    baseUrl: __ENV.BASE_URL || 'http://nginx:8080', // inside the Compose network (decision P6)
    // B7 direct mode: requests round-robin over these base URLs instead of Nginx (Compose service names only)
    baseUrls: parseBaseUrls(__ENV.BASE_URLS),
    uaUser: __ENV.UA_USER === '1',            // B10: user index in the User-Agent for per-user accounting
    hotToken: __ENV.HOT_TOKEN || '',          // B5/B8: a fresh single-user token minted for this run
    tokensFile: __ENV.TOKENS_FILE || '/tokens/bench.json',
    outDir: (__ENV.OUT_DIR || '/results').replace(/\/+$/, ''), // benchmarks/results mounted at run time (O3)
  };
}

// ---------------------------------------------------------------------------------------------------
// Tokens. File format (mint-tokens.py): {"prefix", "admin", "users": [{"id","group","tier","token"}]}.
// Groups: "free" = headroom users (well below the limit), "hot" = throttled users (above the limit).

function jwtExp(token) {
  const parts = token.split('.');
  if (parts.length !== 3) fail('token file contains a malformed JWT');
  return JSON.parse(encoding.b64decode(parts[1], 'rawurl', 's')).exp;
}

export function loadTokens(cfg) {
  const needHeadroom = cfg.profile === 'mixed' || cfg.profile === 'headroom';
  const needThrottled = cfg.profile !== 'headroom';
  // Every token must outlive the whole run (handoff U10): warm-up + measure + 5 min margin. The SharedArray
  // builder runs once per test (not per VU), so every token of the group is checked exactly once.
  const mustLast = Math.floor(Date.now() / 1000) + cfg.warmupS + cfg.measureS + 300;
  const pick = (group) => new SharedArray(`${group}-tokens`, () => {
    const doc = JSON.parse(open(cfg.tokensFile));
    const tokens = doc.users.filter((u) => u.group === group && u.tier === 'FREE').map((u) => u.token);
    let earliest = Infinity;
    let expiring = 0;
    for (const t of tokens) {
      const exp = jwtExp(t);
      if (typeof exp !== 'number') fail(`a "${group}" token in ${cfg.tokensFile} has no numeric exp claim`);
      if (exp < mustLast) expiring += 1;
      earliest = Math.min(earliest, exp);
    }
    if (expiring > 0) {
      fail(`${expiring} of ${tokens.length} "${group}" tokens expire before the run ends `
        + `(earliest exp ${earliest} < ${mustLast}); mint them again with a longer --ttl`);
    }
    return tokens;
  });
  const headroom = needHeadroom ? pick('free') : [];
  const throttled = needThrottled ? pick('hot') : [];
  if (needHeadroom && headroom.length === 0) fail(`no "free" (headroom) FREE-tier tokens in ${cfg.tokensFile}`);
  if (needThrottled && throttled.length === 0) fail(`no "hot" (throttled) FREE-tier tokens in ${cfg.tokensFile}`);
  return { headroom, throttled };
}

// ---------------------------------------------------------------------------------------------------
// Request mixes. Deterministic round-robin over users, so per-user rates are exact:
//   mixed:     every 5th request -> throttled users (20 %), the rest -> headroom users (80 %)
//   headroom:  all requests over the headroom users
//   throttled: all requests over the throttled users
//   hotkey:    all requests from ONE throttled user
// Per-user request rate (requests/min) = share × RATE × 60 / users; see b1-load-levels.js for the table.

export function chooseUser(cfg, tokens, i) {
  let k;
  switch (cfg.profile) {
    case 'headroom':
      k = i % tokens.headroom.length;
      return { group: 'headroom', index: `h${k}`, token: tokens.headroom[k] };
    case 'throttled':
      k = i % tokens.throttled.length;
      return { group: 'throttled', index: `t${k}`, token: tokens.throttled[k] };
    case 'hotkey':
      // a fresh per-run user when HOT_TOKEN is given (clean state for bounds), else the first throttled user
      return { group: 'throttled', index: 'hot', token: cfg.hotToken || tokens.throttled[0] };
    default: // mixed
      if (i % 5 === 0) {
        k = (i / 5) % tokens.throttled.length;
        return { group: 'throttled', index: `t${k}`, token: tokens.throttled[k] };
      }
      // the n-th non-throttled request (n = i - ⌊i/5⌋ - 1) walks through ALL headroom users in order
      k = (i - Math.floor(i / 5) - 1) % tokens.headroom.length;
      return { group: 'headroom', index: `h${k}`, token: tokens.headroom[k] };
  }
}

// ---------------------------------------------------------------------------------------------------
// Metrics. Scenario tags (phase, ...) are attached to every sample emitted inside a scenario, including
// these custom metrics, so warm-up and measure are always separable. 429 is an expected outcome.

export const accepted = new Counter('accepted');
export const rejected = new Counter('rejected');
export const unexpected = new Counter('unexpected');
export const unexpectedRate = new Rate('unexpected_rate');

http.setResponseCallback(http.expectedStatuses(200, 429)); // http_req_failed = neither 200 nor 429

export function userAgent(cfg, phase, user) {
  // Starts with "k6/" so the Nginx log filter (UA contains "k6/") keeps working (O4); the phase and the run
  // id let collectors split the access log by phase and run without relying on 1 s timestamps.
  const base = `k6/ratelimiter-bench phase=${phase} benchmark=${cfg.benchmark} algorithm=${cfg.algorithm} run=${cfg.runId}`;
  return cfg.uaUser && user ? `${base} user=${user}` : base;
}

// setup(): the absolute start time of the scenarios (ms since the epoch). It is logged for the runner (fault
// injection, measurement window) and returned, so it also appears as setup_data.t0 in the k6 summary.
export function benchSetup() {
  const t0 = Date.now();
  console.log(`BENCH_T0=${t0}`);
  return { t0 };
}

// The phase of the running iteration, from the scenario name (scenarioName(): "<benchmark>_<phase>").
export function currentPhase() {
  return exec.scenario.name.endsWith('_measure') ? 'measure' : 'warmup';
}

export function sendRequest(cfg, tokens, override = {}) {
  const phase = currentPhase();
  const i = exec.scenario.iterationInTest;
  const user = override.user || chooseUser(cfg, tokens, i);
  const base = cfg.baseUrls.length ? cfg.baseUrls[i % cfg.baseUrls.length] : cfg.baseUrl;
  const path = override.path || ENDPOINT;
  const res = http.get(`${base}${path}`, {
    headers: { Authorization: `Bearer ${user.token}`, 'User-Agent': userAgent(cfg, phase, user.index) },
    tags: { name: path, usergroup: user.group, ...(override.tags || {}) },
  });
  const tags = { usergroup: user.group, ...(override.tags || {}) }; // "group" is a k6 system tag
  if (res.status === 200) accepted.add(1, tags);
  else if (res.status === 429) rejected.add(1, tags);
  else unexpected.add(1, tags);
  unexpectedRate.add(res.status !== 200 && res.status !== 429, tags);
  return res;
}

// ---------------------------------------------------------------------------------------------------
// Options: two constant-arrival-rate scenarios (open model). The measure scenario starts exactly
// when warm-up ends; its tag phase=measure selects the only samples used for thresholds and reporting.

export function scenarioName(cfg, phase) {
  return `${cfg.benchmark.toLowerCase()}_${phase}`;
}

export function buildOptions(cfg) {
  const scenarios = {};
  const base = {
    executor: 'constant-arrival-rate',
    rate: cfg.rate,
    timeUnit: '1s',
    preAllocatedVUs: cfg.preVUs,
    maxVUs: cfg.maxVUs,
    gracefulStop: '10s',
  };
  if (cfg.warmupS > 0) {
    scenarios[scenarioName(cfg, 'warmup')] = {
      ...base, preAllocatedVUs: cfg.warmPreVUs, maxVUs: cfg.preVUs, duration: `${cfg.warmupS}s`, tags: { phase: 'warmup' },
    };
  }
  scenarios[scenarioName(cfg, 'measure')] = {
    ...base, startTime: `${cfg.warmupS}s`, duration: `${cfg.measureS}s`, tags: { phase: 'measure' },
  };
  const measureScenario = scenarioName(cfg, 'measure');
  const expected = cfg.rate * cfg.measureS;
  const always = (kind) => [kind === 'trend' ? 'max>=0' : 'count>=0']; // exposes a sub-metric in the summary
  // Reported, never pass/fail here: counts per phase, measure-phase latency per user group.
  const thresholds = {};
  for (const phase of PHASES) {
    for (const m of ['http_reqs', 'accepted', 'rejected', 'unexpected']) thresholds[`${m}{phase:${phase}}`] = always('counter');
  }
  Object.assign(thresholds, {
    [`http_req_duration{phase:measure}`]: always('trend'),
    [`http_req_duration{phase:measure,usergroup:headroom}`]: always('trend'),
    [`http_req_duration{phase:measure,usergroup:throttled}`]: always('trend'),
    [`http_req_failed{phase:measure}`]: ['rate>=0'],
    [`dropped_iterations{scenario:${scenarioName(cfg, 'warmup')}}`]: always('counter'),
  });
  // Invalid-run conditions that k6 itself can see (measure phase only). Assigned LAST so that no
  // reporting entry above can replace them (the same sub-metric key holds one threshold list).
  Object.assign(thresholds, {
    [`dropped_iterations{scenario:${measureScenario}}`]: ['count==0'],
    [`http_reqs{phase:measure}`]: [`count>=${Math.ceil(0.99 * expected)}`], // achieved >= 99 % of target
    [`unexpected_rate{phase:measure}`]: ['rate<0.001'],                     // unexpected statuses < 0.1 %
  });
  return {
    scenarios,
    thresholds,
    discardResponseBodies: true, // status and headers are enough; saves k6 CPU and memory
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    tags: { benchmark: cfg.benchmark, algorithm: cfg.algorithm, profile: cfg.profile, rate: String(cfg.rate) },
  };
}

// ---------------------------------------------------------------------------------------------------
// Summary: full JSON into the run directory (created by run.sh before k6 starts), one line on stdout.

export function summaryOutputs(cfg, data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const line = ['warmup', 'measure'].map((p) => `${p}: requests=${count(`http_reqs{phase:${p}}`)} `
    + `accepted=${count(`accepted{phase:${p}}`)} rejected=${count(`rejected{phase:${p}}`)} `
    + `unexpected=${count(`unexpected{phase:${p}}`)}`).join(' | ')
    + ` | total: accepted=${count('accepted')} rejected=${count('rejected')} unexpected=${count('unexpected')}`
    + ` dropped_iterations=${count('dropped_iterations')}\n`;
  return {
    [`${cfg.outDir}/${cfg.runId}/k6-summary.json`]: JSON.stringify(data, null, 2),
    [`${cfg.outDir}/${cfg.runId}/k6-config.json`]: JSON.stringify(cfg, null, 2),
    stdout: line,
  };
}

// ---------------------------------------------------------------------------------------------------
// Additional token groups (e.g. ENTERPRISE users for the fail-closed endpoint in F1–F5), with the same
// every-token expiry check as loadTokens.

export function loadGroupTokens(cfg, file, group, tier) {
  const mustLast = Math.floor(Date.now() / 1000) + cfg.warmupS + cfg.measureS + 300;
  const tokens = new SharedArray(`${group}-${tier}-tokens`, () => {
    const doc = JSON.parse(open(file));
    const list = doc.users.filter((u) => u.group === group && u.tier === tier).map((u) => u.token);
    const expiring = list.filter((t) => jwtExp(t) < mustLast).length;
    if (expiring > 0) fail(`${expiring} of ${list.length} "${group}" tokens in ${file} expire before the run ends`);
    return list;
  });
  if (tokens.length === 0) fail(`no "${group}" ${tier} tokens in ${file}`);
  return tokens;
}

// ---------------------------------------------------------------------------------------------------
// Option builders for the other executors. Each keeps the warmup/measure naming (scenario names end with
// "_warmup" / "_measure") so currentPhase(), the phase tags and the validity thresholds work unchanged.

function reportingThresholds(cfg, extra = {}) {
  const t = {};
  const always = (kind) => [kind === 'trend' ? 'max>=0' : 'count>=0'];
  for (const phase of PHASES) {
    for (const m of ['http_reqs', 'accepted', 'rejected', 'unexpected']) t[`${m}{phase:${phase}}`] = always('counter');
  }
  t['http_req_duration{phase:measure}'] = always('trend');
  t['http_req_failed{phase:measure}'] = ['rate>=0'];
  return Object.assign(t, extra);
}

function common(cfg, scenarios, thresholds) {
  return {
    scenarios,
    thresholds,
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    tags: { benchmark: cfg.benchmark, algorithm: cfg.algorithm, profile: cfg.profile, rate: String(cfg.rate) },
  };
}

function warmupScenario(cfg, rate) {
  return {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${cfg.warmupS}s`,
    preAllocatedVUs: Math.max(200, Math.ceil((rate * 0.2))), maxVUs: Math.max(250, Math.ceil(rate * 0.25)), gracefulStop: '10s', tags: { phase: 'warmup' },
  };
}

// Burst (B5): optional warm-up at WARMUP_RATE, then RATE requests/s for BURST_S seconds (10,000 in 1 s).
export function buildBurstOptions(cfg) {
  const burstS = intEnv('BURST_S', 1, 1, 60);
  const warmRate = intEnv('WARMUP_RATE', 1000, 1, 50000);
  const vus = intEnv('BURST_VUS', 2000, 1, 20000);  // 10,000 requests in 1 s with up to 200 ms latency
  const scenarios = {};
  if (cfg.warmupS > 0) scenarios[scenarioName(cfg, 'warmup')] = warmupScenario(cfg, warmRate);
  const m = scenarioName(cfg, 'measure');
  scenarios[m] = {
    executor: 'constant-arrival-rate', rate: cfg.rate, timeUnit: '1s', duration: `${burstS}s`, startTime: `${cfg.warmupS}s`,
    preAllocatedVUs: vus, maxVUs: Math.max(vus, intEnv('BURST_MAX_VUS', 3000, 1, 20000)), gracefulStop: '30s',
    tags: { phase: 'measure' },
  };
  const expected = cfg.rate * burstS;
  return common(cfg, scenarios, reportingThresholds(cfg, {
    [`dropped_iterations{scenario:${m}}`]: ['count==0'],
    'http_reqs{phase:measure}': [`count>=${Math.ceil(0.99 * expected)}`],
    'unexpected_rate{phase:measure}': ['rate<0.001'],
  }));
}

// Concurrency (B6): VUS virtual users, one request each, released together (closed model on purpose).
export function buildConcurrencyOptions(cfg) {
  const vus = intEnv('VUS', 1000, 1, 20000);
  return common(cfg, {
    [scenarioName(cfg, 'measure')]: { executor: 'per-vu-iterations', vus, iterations: 1, maxDuration: '60s', tags: { phase: 'measure' } },
  }, reportingThresholds(cfg, { 'http_reqs{phase:measure}': [`count==${vus}`], 'unexpected{phase:measure}': ['count==0'] }));
}

// Stress ramp (B2): warm-up at START_RATE, then steps of STEP_RATE every STEP_S seconds up to RATE (the top).
export function rampSteps(cfg) {
  const start = intEnv('START_RATE', 1000, 1, 50000);
  const step = intEnv('STEP_RATE', 1000, 1, 50000);
  const steps = [];
  for (let r = start; r <= cfg.rate; r += step) steps.push(r);
  return { start, step, stepS: intEnv('STEP_S', 30, 5, 600), steps };
}

export function buildRampOptions(cfg) {
  const { start, stepS, steps } = rampSteps(cfg);
  const scenarios = {};
  if (cfg.warmupS > 0) scenarios[scenarioName(cfg, 'warmup')] = warmupScenario(cfg, start);
  const stages = [];
  for (const r of steps) stages.push({ target: r, duration: '1s' }, { target: r, duration: `${stepS - 1}s` });
  scenarios[scenarioName(cfg, 'measure')] = {
    executor: 'ramping-arrival-rate', startRate: start, timeUnit: '1s', stages, startTime: `${cfg.warmupS}s`,
    preAllocatedVUs: cfg.preVUs, maxVUs: cfg.maxVUs, gracefulStop: '10s', tags: { phase: 'measure' },
  };
  const extra = {};
  for (const r of steps) {
    extra[`http_reqs{step:${r}}`] = ['count>=0'];
    extra[`accepted{step:${r}}`] = ['count>=0'];
    extra[`unexpected{step:${r}}`] = ['count>=0'];
    extra[`http_req_duration{step:${r}}`] = ['max>=0'];
  }
  return common(cfg, scenarios, reportingThresholds(cfg, extra));
}

// The ramp step (target rate) of the current iteration, from the elapsed time since setup().
export function currentStep(cfg, data) {
  const { start, step, stepS, steps } = rampSteps(cfg);
  const idx = Math.floor((Date.now() - data.t0 - cfg.warmupS * 1000) / (stepS * 1000));
  return String(Math.min(steps[steps.length - 1], start + Math.max(0, idx) * step));
}

// Failure campaigns (F1–F5): two parallel constant-rate streams per phase, the fail-open benchmark endpoint
// (/api/v1/demo/profile, FREE users) and the fail-closed endpoint (/api/v1/demo/enterprise, ENTERPRISE users).
export function buildFailureOptions(cfg) {
  const share = intEnv('ENT_SHARE_PCT', 10, 1, 99);
  const ent = Math.round((cfg.rate * share) / 100);
  const prof = cfg.rate - ent;
  const scenarios = {};
  // VUs split by share: the fail-closed stream gets 1/4 of the profile stream's VUs (it carries 10 % of the load)
  const vusFor = (endpoint, phase) => {
    const base = phase === 'warmup' ? cfg.warmPreVUs : cfg.preVUs;
    return endpoint === 'enterprise' ? Math.max(30, Math.ceil(base / 4)) : base;
  };
  const mk = (rate, endpoint, phase, extra) => ({
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', preAllocatedVUs: vusFor(endpoint, phase),
    maxVUs: Math.ceil(vusFor(endpoint, phase) * 1.25),
    gracefulStop: '10s', exec: endpoint === 'enterprise' ? 'enterprise' : 'default', tags: { phase, endpoint }, ...extra,
  });
  if (cfg.warmupS > 0) {
    scenarios.f_profile_warmup = mk(prof, 'profile', 'warmup', { duration: `${cfg.warmupS}s` });
    scenarios.f_enterprise_warmup = mk(ent, 'enterprise', 'warmup', { duration: `${cfg.warmupS}s` });
  }
  scenarios.f_profile_measure = mk(prof, 'profile', 'measure', { duration: `${cfg.measureS}s`, startTime: `${cfg.warmupS}s` });
  scenarios.f_enterprise_measure = mk(ent, 'enterprise', 'measure', { duration: `${cfg.measureS}s`, startTime: `${cfg.warmupS}s` });
  return common(cfg, scenarios, reportingThresholds(cfg, {
    'dropped_iterations{scenario:f_profile_measure}': ['count==0'],
    'dropped_iterations{scenario:f_enterprise_measure}': ['count==0'],
    'http_reqs{phase:measure}': [`count>=${Math.ceil(0.99 * cfg.rate * cfg.measureS)}`],
    'http_req_duration{phase:measure,endpoint:profile}': ['max>=0'],
    'http_req_duration{phase:measure,endpoint:enterprise}': ['max>=0'],
  }));
}
