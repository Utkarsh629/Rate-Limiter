# Phase 3B — analysis, optimisation decisions and acceptance

Companion to `report.md` in this directory, which `scripts/bench/report.py` generated from the saved runs in
`benchmarks/results/`. Every figure here is taken from `report.md` or from the named run artifacts; nothing is
measured or typed independently. Environment: one laptop (Apple M4, 10 cores, 16 GB; Docker Desktop VM with 10
CPUs and ~7.6 GiB), all services and the load generator on the same machine. **Results are relative and
machine-specific; they are not production capacity.**

## 1. Campaign accounting

212 runs: **153 valid, 59 invalid**. Invalid runs are kept in `benchmarks/results/` with their reasons and are
never used in result tables.

| Scenario | Valid | Invalid | Notes |
|---|---|---|---|
| B0 baseline 100 RPS | 9 | 0 | 3 per algorithm |
| B1 load levels | 36 | 32 | 3 valid per algorithm at 1,000 / 2,500 / 5,000 / 7,500 RPS. 10,000 RPS: 11 attempts, all invalid, not retried (decision). 9,000 RPS (MST confirmation): 9 attempts, all invalid |
| B2 stress ramp | 3 | 0 | knee 11,000 RPS in all three |
| B3 overhead A/B | 12 | 0 | 1,000 and 5,000 RPS, limiter on/off |
| B4 headroom vs throttled | 18 | 8 | 3 valid per configuration |
| B5 burst | 9 | 1 | |
| B6 concurrency | 3 | 3 | the 3 invalid runs came from two tooling bugs (fixed); all 6 admitted exactly 100 of 1,000 |
| B7 distributed | 18 | 2 | Nginx and direct |
| B8 hot key | 18 | 1 | 2,000 and 5,000 RPS |
| B9 pooling | 18 | 2 | 1,000 / 5,000 / 7,500 RPS, pool off vs pool-16 |
| B10 correctness | 9 | 0 | |
| F1–F5 | 0 | 10 | timelines are the deliverable; invalid by the generic rules for expected reasons (§6) |

Every invalid benchmark run failed on `dropped_iterations` (k6 could not keep the arrival rate), sometimes together
with the achieved-rate rule, except the three B6 tooling-bug runs and F4 (§6).

## 2. Throughput

- **Highest validated B1 load level: 7,500 RPS** for all three algorithms, 3 valid runs each, with no non-429
  errors. At that level: E2E P99 12.3 ms (TB), 20.4 ms (FW), 30.1 ms (SW); limiter P99 1.27 / 1.29 / 1.69 ms.
- **Maximum sustainable throughput (approved criteria, constant rate): 7,500 RPS** for every algorithm. The
  constant-rate confirmation at 9,000 RPS (decision) produced 0 valid runs in 9 attempts. Each dropped 34–1,833 of
  ~2.2 million iterations (< 0.1 %) during short stalls, so 9,000 is not sustainable under the rules.
- **B2 knee: 11,000 RPS** in all three ramps (30-second steps). Every step up to 11,000 met every criterion (E2E P99
  ≤ 31.4 ms). From 12,000 RPS the k6 container reached 93–100 % of its 2-CPU limit; achieved throughput fell to
  94 % / 82 % / 66 % at 13,000 / 14,000 / 15,000. The system stayed inside the latency criteria at 12,000 (E2E P99
  37–71 ms). **The knee is set by the load generator, not by the rate limiter.**
- **10,000 RPS is not achieved on this laptop** (target only): 11 constant-rate attempts, all invalid. Evidence:
  9,758 RPS achieved with 43,521 dropped iterations; k6 CPU rising to 177 % of one core; app instances at ~97 %
  CPU (about two-thirds of their 1.5-CPU quota).
- Requirement target 5,000 RPS: **met** — valid and inside every criterion for all three algorithms (E2E P99
  5.6–6.7 ms, limiter P99 0.78–1.07 ms, 0 non-429 errors).

## 3. Latency views (kept separate) and limiter overhead

- **In-process limiter overhead** at 5,000 RPS (B1): avg 0.13–0.14 ms, P95 0.25–0.28 ms, P99 0.78–1.07 ms.
  Targets (< 5 / 10 / 20 ms): **met** at every validated level, with ≥ 10× margin.
- **Redis round trip (client side)** P99 tracks the limiter P99 closely (0.76–1.05 ms at 5,000 RPS): the Redis
  call is almost all of the limiter's time.
- **Redis server side** (`INFO latencystats`, EVALSHA): P50 6–9 µs, P99 38–47 µs at 2,500–7,500 RPS. Redis
  itself is never the bottleneck; the round trip is dominated by the network and client.
- **B3 A/B** (token bucket): at 5,000 RPS the limiter adds 0.13 ms at E2E P50, 0.56 ms at P95 and 2.3 ms at P99.
  At 1,000 RPS the differences are within run-to-run noise.

## 4. Algorithm comparison (same 120/60 s allowance)

- **CPU cost:** at 7,500 RPS the average instance CPU is TB 60 % < FW 66 % < SW 78 % of one core. GC pause per
  180 s is TB 5.3 s < FW 5.7 s < SW 7.5 s (sum of three instances). The sliding window is the most expensive (two
  window counters).
- **Latency:** at 7,500 RPS the E2E P99 ranks TB 12.3 ms < FW 20.4 ms < SW 30.1 ms. At ≤ 5,000 RPS all three are
  within 1 ms of each other.
- **Correctness:** zero over-admission for every algorithm in every run (§5).

## 5. Correctness

- **B6:** exactly **100 accepted / 900 rejected** of 1,000 simultaneous requests across 3 instances, in all 6 runs.
- **B5:** burst of 10,000 requests in 1 s from one fresh user. TB accepted 121–122 against a bound of 125; FW and
  SW accepted exactly 120 against 120. **Zero over-admission.**
- **B10:** 100 users each at 10× the allowance for 4 minutes, every user checked against its bound. **Zero
  over-admission in all 9 runs.** Token bucket admitted 599 per user against a bound of 603 (99.3 % utilisation);
  fixed and sliding window admitted 83–100 % of their window bound (under-admission from window alignment is
  expected).
- **Counts reconcile exactly** (k6 = Nginx = per-instance counters = Prometheus = `/stats` = Redis EVALSHA) in
  every valid run.

## 6. Failure campaigns F1–F5 (2,000 RPS; fail-open `/profile` + fail-closed `/enterprise`)

| Fault | Fail-open endpoint | Fail-closed endpoint | Breakers (per instance) | Recovery |
|---|---|---|---|---|
| F1 Redis paused 20 s | 37,757 × 200, **0 × 429** during the outage | 4,030 × 503, **0 × 429** | OPEN at +1/+4/+5 s, CLOSED at +24–26 s | 5 s open wait + 3 probes after unpause |
| F2 Redis restart | brief fail-open (9,426 decisions) | 251 × 503 during the restart | OPEN ~4 s each | CLOSED by +9–11 s; quotas reset (no persistence, documented) |
| F3 app2 killed 30 s | **no client errors**: Nginx failover to app1/app3 | no errors during the outage | app2 OPEN briefly after its restart | 122 × 503 right after rejoin (cold JVM: Redis timeouts) |
| F4 MySQL stopped 60 s | **no effect** on limiting | no effect | never opened | policy API 503; readiness 200 on all instances |
| F5 Redis blocked 142 ms, then 388 ms | 142 ms blocks absorbed (< 250 ms timeout) | 5,263 × 503 in the 388 ms phase | OPEN only in the 388 ms phase | CLOSED 1–6 s after the blocks ended |

F5 used a calibrated pure-CPU Lua loop sent with the standard `EVAL` (Redis measured each block); `DEBUG` stays
disabled. Every F run is INVALID under the generic rules, as an outcome of the fault: dropped iterations while
requests waited on the 250 ms timeout. F4 is the exception: its admin API check during the outage is a
token-bucket decision the reconciliation counts as foreign. F2 and F3 also fail count reconciliation by design
(Redis data lost; restarted instance's counters reset).

## 7. Bottleneck analysis

At 7,500 RPS **no component is saturated**:

| Component | Load |
|---|---|
| App instances | 60–78 % of one core (quota 150 %); Tomcat busy threads ≤ 24 of 200 |
| Redis | 19–24 % CPU (~0.2 core of 1); 29,000–35,000 ops/s |
| Nginx | 44–55 % |
| k6 | ≤ 59 % of its limit |

What prevents higher **valid constant-rate** throughput:

1. **Short latency stalls of 100 ms – 1.1 s** (E2E max) that outlast k6's pre-allocated VUs and drop iterations.
   They are mostly not GC pauses (max GC pause 6–12 ms in most runs, 108–121 ms twice) and mostly not the limiter
   (limiter max ≤ 24 ms except one 124 ms case). They arise outside the application, in the Docker Desktop
   VM/network path and the load generator.
2. **The load generator's 2-CPU / 1 GiB budget:** k6 saturates its CPU from ~12,000 RPS (B2), and keeps every
   latency sample in memory (fixed with `GOMEMLIMIT`, §8).
3. At 10,000 RPS the app instances approach their CPU quota as well (~97 % of one core).

## 8. Optimisations

**Applied to the system under test: none.** Each candidate from the plan was evaluated against the evidence:

| Candidate | Evidence | Decision |
|---|---|---|
| Redis connection pooling (B9) | pool-16 vs off: limiter P99 0.95 vs 0.76 ms (5,000 RPS), 1.72 vs 1.36 ms (7,500); E2E P99 equal or worse | **Rejected** (keep the shared connection); the 8/32 sweep is skipped (plan: only if 16 helps) |
| JVM GC flags | GC pause max 6–12 ms typical; stalls are 100 ms – 1 s and do not line up | **Rejected**: no evidence |
| Tomcat threads | busy ≤ 24 of 200 | **Rejected** |
| Nginx workers/keepalive | ≤ 55 % CPU; failover worked (F3) | **Rejected** |
| Redis io-threads | ~0.2 core, server P99 < 50 µs | **Rejected** |
| Redis command timeout (250 ms, D42) | cold-start fail-open in the clean smoke test and F3; warm B6 always exact | **Not changed** (approved decision); finding recorded |

**Changes to the measurement tooling** (k6 only; validity rules unchanged; each documented in the files):

- k6 runs with `GOMEMLIMIT=850MiB`. Without it, k6 hit its 1 GiB limit at 7,500 RPS and collapsed to 3,094 RPS.
- Pre-allocated VUs sized for the latency tail (VUs ≈ rate × 200 ms, 200–1,000).
- Scrape-completeness and k6-CPU checks marked n/a for ~1-second B5/B6 windows (they cannot apply there).
- F5 mechanism: calibrated Lua loop instead of a `TIME` loop (Redis 7 freezes `TIME` inside scripts).

## 9. Acceptance criteria (plan, Phase 3B)

| # | Criterion | Status |
|---|---|---|
| AC1 | `run.sh` produces a complete artefact directory (1,000 RPS run) | **Met** |
| AC2 | validity checks automatic, INVALID with reasons | **Met** (59 invalid runs, each with reasons) |
| AC3 | reconciliation k6/Nginx/Prometheus/`/stats` | **Met** — exact in every valid run |
| AC4 | no hand-typed numbers in docs/11 | **Met**: docs/11 is rebuilt from `report.md` by `build_docs11.py` |
| AC5 | 3 valid repetitions per configuration, or invalid runs recorded | **Met**; 9,000 and 10,000 RPS recorded as invalid |
| AC6 | all load levels attempted for all algorithms; 10,000 a target | **Met**; 10,000 attempted (11×), not achieved |
| AC7 | MST determined and confirmed at constant rate | **Met**: 7,500 RPS confirmed; knee 11,000 (load-generator bound); 9,000 confirmation invalid |
| AC8 | limiter, Redis RTT, E2E reported separately + A/B delta | **Met** |
| AC9 | zero over-admission (B10); B6 exactly 100/1,000 | **Met** |
| AC10 | algorithm and pooling comparison tables | **Met** |
| AC11 | CPU/memory/JVM/Redis per valid run | **Met** (resource tables in `report.md`; raw CSV per run) |
| AC12 | F1–F5 with per-second timelines | **Met** (`report.md` §F1–F5, Nginx per-second timelines, breaker timelines, logs) |
| AC13 | bottleneck identified with evidence | **Met** (§7) |
| AC14 | every optimisation before/after, kept or reverted | **Met**: none applied; every candidate rejected with evidence (§8) |
| AC15 | no Lua, algorithm or security change | **Met** (no application code changed) |
| AC16 | 3B documentation updated and approved | **Met**: docs/10, 11, 12, 13, 14 and `PROJECT_PROGRESS.md` updated; approved 2026-11-12 |

## 10. Reproduce

```bash
scripts/stack-up.sh
python3 scripts/bench/mint-tokens.py --free 10000 --hot 100 --ttl 172800 --out load-tests/k6/.tokens/bench.json
python3 scripts/bench/mint-tokens.py --enterprise 2000 --ttl 172800 --out load-tests/k6/.tokens/bench-enterprise.json
python3 -m unittest discover -s scripts/bench/tests -t scripts/bench/tests
scripts/bench/run.sh run --scenario B1 --algorithm token_bucket --rate 1000 --rep 1 --dry-run
python3 scripts/bench/campaign.py benchmarks            # B6, B5, B10, B0, B1, B4, B8, B7, B2
python3 scripts/bench/campaign.py b3                    # also: b9, knee, b9knee
python3 scripts/bench/campaign.py failures --reps 1 --max-attempts 1 --count-complete
python3 scripts/bench/report.py --out benchmarks/reports/<name>
```

## 11. Regression and smoke checks after the campaign

- `./mvnw clean verify`: **158 unit + 152 integration = 310 tests, BUILD SUCCESS**, the same 310-test baseline (no
  application code changed in Phase 3B).
- Benchmark tooling: 49 Python unit tests OK; `setup-policies.sh selftest` 75/75.
- `scripts/stack-smoke.sh` (current stack, not `--clean`, so the Prometheus history behind these results is kept):
  - Run `20261112-045040`, about 3 minutes after the instances were recreated (no JIT warm-up): **FAILED, AC14 only.**
    The 1,000-concurrent bursts produced 11 / 333 / 15 fail-open responses as app2's breaker opened on 250 ms Redis
    command timeouts (the same cold-start effect as the clean smoke test before the campaign and F3).
  - Run `20261112-045257`, the same stack once warm: **PASSED 37/37.**
- **Finding (unresolved, not changed):** with a cold JVM, a 1,000-request simultaneous burst on these CPU-limited
  instances can exceed the approved 250 ms Compose Redis timeout (D42) and fail open. Warm instances are exact (B6:
  6 of 6 runs; smoke 4 of 4 rounds). Options for review: a JIT warm-up before readiness, or revisiting the timeout
  with evidence. Neither was applied.
- **Investigation** (`benchmarks/investigations/cold-start/`, `docs/13` "Cold-start fail-open"): the cause is
  CPU-quota throttling of each 1.5-CPU instance during a burst. JIT compilation is 0.5–1.5 s of the 0.6–1.6 s of CPU a
  burst costs per instance; the throttling stalls the Lettuce I/O thread and pushes client-side Redis calls to
  181–213 ms. A steady 1,000 RPS warm-up does not prevent it. **Status: unresolved**; options listed in docs/13.
- After the documentation changes: smoke run `20261112-093518` **PASSED 37/37**; tooling tests 49/49; selftest 75/75;
  `./mvnw clean verify` re-run (see the final checklist).
