# Phase 3B benchmark report

Generated 2026-11-11T11:30:33 by scripts/bench/report.py from 5 saved runs in benchmarks/results/ (3 valid). ⚠ = spread above 10 % across repetitions. Cells show the median (min–max) over valid repetitions.

## Environment (from the latest run's environment record)

- Host: Apple M4, 10 CPUs, 16 GiB; ProductName: macOS ProductVersion: 27.2.1 BuildVersion: 26B131
- Docker: 28.5.1, 10 CPUs, 8218034176 bytes, Docker Desktop
- Git: 5d938e1006c21e7dbe18da981838a238431a5da3 on phase-3b/benchmark-infrastructure
- All services share one laptop; results are relative and machine-specific, not production capacity.

### B0 baseline (100 RPS)

No runs.

### B1 load levels (mixed profile, Nginx)

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 1,000 | 3/2 | 1,000 (1,000–1,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 0.53 (0.52–0.54) | 0.86 (0.78–0.88) ⚠ | 1.63 (1.47–1.79) ⚠ | 42.6 (6.8–47.9) ⚠ | 0.152 (0.152–0.154) | 0.139 (0.138–0.143) | 0.205 (0.204–0.214) | 0.528 (0.502–0.535) | 0.135 (0.131–0.140) | 0.515 (0.489–0.525) | 14.0 (14.0–15.0) | 63.2 (59.1–63.2) |

### B1 load levels (mixed profile, Nginx) — resources

CPU % from docker stats (100 % = one core; app limit 150 %, Redis 100 %, Nginx 100 %), JVM/Redis from Prometheus.

| configuration | app CPU avg % (max inst) | app CPU peak % | JVM process CPU | heap max MiB | GC pause ms | Tomcat busy max | Redis CPU % | Redis main-thread cores | Redis ops/s | Redis clients | Redis MiB | Nginx CPU % | k6 CPU % of limit (worst 5 s) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 1,000 | 13 (12–13) | 21 (20–22) | 0.08 (0.08–0.09) | 112 (112–113) | 1,253 (1,179–1,255) | 3 (3–3) | 5 (5–5) | 0.04 (0.04–0.05) | 5,009 (5,009–5,009) | 7 (7–7) | 1.8 (1.8–1.8) | 10 (9–10) | 13 (13–13) |

### B4 headroom vs throttled (5,000 RPS)

No runs.

### B5 burst (10,000 requests in 1 s, one fresh user)

No runs.

### B6 concurrency

No runs.

### B7 distributed: Nginx vs direct (5,000 RPS)

No runs.

### B8 hot key

No runs.

### B10 correctness under load (throttled, 2,000 RPS)

No runs.

### B3 overhead A/B

No runs.

### B9 Redis pooling

No runs.

## Maximum sustainable throughput (highest tested B1 level meeting every criterion in 3 valid runs)

Criteria: run valid; achieved ≥ 99 %; non-429 errors < 0.1 %; E2E P99 < 100 ms; limiter P99 < 20 ms. Only the tested levels can be claimed; the knee search (B2) narrows the region.

- **token_bucket**: **1,000 RPS** — 1000: meets the criteria in all 3 valid runs; 2500: not run; 5000: not run; 7500: not run; 10000: not run
- **fixed_window**: not established — 1000: not run; 2500: not run; 5000: not run; 7500: not run; 10000: not run
- **sliding_window**: not established — 1000: not run; 2500: not run; 5000: not run; 7500: not run; 10000: not run

## B2 stress ramp and knee

No B2 runs.

## B3 limiter overhead A/B (token bucket; limiter on vs off; medians, ms)

| RPS | valid on/off | E2E p50 on vs off | E2E p95 on vs off | E2E p99 on vs off | in-process limiter histogram (on) |
|---|---|---|---|---|---|
| 1000 | incomplete |  |  |  |  |
| 5000 | incomplete |  |  |  |  |

## B7 per-instance load split (share of decisions per instance)

No runs.

## Correctness (B5 burst bound, B6 exactly 100 of 1,000, B10 per-user bounds)

No runs.

## F1–F5 failure and recovery under load

No failure runs.

## Targets (requirements) vs measured

| metric | target | measured |
|---|---|---|
| throughput | 5,000 RPS (3 instances) | token_bucket: 1000; fixed_window: not established; sliding_window: not established |
| limiter overhead avg / P95 / P99 | < 5 / 10 / 20 ms | see B1 tables (in-process histogram, per run) |
| enforcement accuracy | zero over-admission | see the correctness table (B5, B6, B10) |
| non-429 error rate | < 0.1 % | see the error columns; invalid runs listed separately |
| load levels | 1,000 → 10,000 RPS | 10,000 RPS reported as achieved only where a valid run shows it |

## Invalid and failed runs (kept, never reported as results)

| run | status | reasons |
|---|---|---|
| 20261111T053559Z-b1-token_bucket-1000-r1 | complete | dropped_iterations: k6 dropped 17 iterations: the load generator could not keep the arrival rate |
| 20261111T054529Z-b1-token_bucket-1000-r3 | complete | dropped_iterations: k6 dropped 17 iterations: the load generator could not keep the arrival rate |
