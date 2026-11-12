# 11 — Performance Benchmarks

> Status: **Phase 3B measured.** Every result table on this page is copied verbatim from
> `benchmarks/reports/phase-3b-final/report.md`, which `scripts/bench/report.py` generates from the saved runs in
> `benchmarks/results/` (212 runs, valid and invalid). This page is rebuilt by
> `benchmarks/reports/phase-3b-final/build_docs11.py`; no number is typed by hand. The analysis, optimisation
> decisions and acceptance checklist are in `benchmarks/reports/phase-3b-final/analysis.md`; failure timelines are
> in `13-failure-handling.md`.

## Same-machine limitation

The load generator (k6), Nginx, three JVMs, Redis, MySQL, Prometheus and Grafana all run on one laptop (Apple M4,
10 cores, 16 GB RAM; Docker Desktop VM with 10 CPUs and ~7.6 GiB) with fixed container limits (each app 1.5 CPU /
1 GiB, Redis 1 CPU, Nginx 1 CPU, k6 2 CPU / 1 GiB). k6 competes with the system under test for CPU, and container
networking stays inside one VM. **Results are relative and specific to this machine; they are not production
capacity.**

## Method (approved; applied to every run)

- Open model: k6 `constant-arrival-rate` (stress: `ramping-arrival-rate` in 30-second steps; concurrency:
  `per-vu-iterations`, 1,000 VUs × 1; burst: 10,000 requests in 1 s).
- Every run: Redis reset (`FLUSHDB` on the verified Compose Redis only), 60 s warm-up, 180 s measurement, cooldown
  for the final scrape and statistics flush. 3 valid repetitions per configuration; median and min–max reported;
  ⚠ marks a spread above 10 %.
- **A run is INVALID** (kept, never reported as a result) if k6 dropped iterations in the measurement window;
  achieved < 99 % of target; k6 CPU > 85 % of its limit in any 5-second window; non-200/429 statuses > 0.1 %;
  counts do not reconcile; Prometheus scrapes are incomplete; the warm-up overlapped the measurement; or Redis was
  not reset.
- Three latency views are kept separate: in-process limiter overhead (`ratelimiter_request_duration_seconds`),
  Redis round trip (client `ratelimiter_redis_duration_seconds`; server `INFO latencystats`), end-to-end (k6
  `http_req_duration`). Percentiles are never averaged across runs or instances.
- Counts are reconciled exactly over the whole run (decision O2): k6 = Nginx access log = per-instance counters =
  Prometheus = `/stats` (= Redis EVALSHA calls when nothing failed open or closed).
- Benchmark policy (O5): `bench-profile`, 120 requests per 60 s per user for every algorithm (token bucket:
  capacity 120, refill 120 per 60 s). Request mixes: mixed (80 % of requests over 10,000 headroom users, 20 % over
  100 throttled users), headroom, throttled, hot key.
- Load-generator settings (tooling only; no validity rule changed): `GOMEMLIMIT=850MiB` for k6 (without it k6 hit
  its 1 GiB limit at 7,500 RPS and collapsed), VUs pre-allocated for a 200 ms latency tail (200–1,000).

## Decisions taken during the campaign

- 10,000 RPS was attempted for every algorithm (all attempts invalid) and, by decision, not retried; 7,500 RPS is
  the highest validated B1 level on this laptop.
- The maximum sustainable throughput was confirmed at constant rate. The B2 knee (11,000 RPS, load-generator
  bound) was not confirmable at constant rate; 9,000 RPS was attempted as the confirmation rate (all invalid).
- B9 "at the knee" ran at 7,500 RPS, the highest constant rate with valid runs.
- No optimisation of the system under test was applied: every candidate was rejected on measured evidence
  (`analysis.md` §8).

## Results


### B1 load levels: highest validated level, and maximum sustainable throughput

**7,500 RPS is the highest validated B1 load level on this laptop** (3 valid runs per algorithm). 10,000 RPS was attempted 11 times and every attempt was invalid (load generator and instances at their limits); by decision it was not retried. This is a laptop measurement, not a production capacity claim.

Maximum sustainable throughput additionally requires, in all 3 valid runs: achieved ≥ 99 %; non-429 errors < 0.1 %; E2E P99 < 100 ms; limiter P99 < 20 ms. It is established together with the B2 knee below; the highest validated level is not assumed to be sustainable.

- **token_bucket**: **7,500 RPS** — 1000: meets the criteria in all 3 valid runs; 2500: meets the criteria in all 3 valid runs; 5000: meets the criteria in all 3 valid runs; 7500: meets the criteria in all 3 valid runs; 9000: 0 valid of 3 runs; 10000: 0 valid of 3 runs
- **fixed_window**: **7,500 RPS** — 1000: meets the criteria in all 3 valid runs; 2500: meets the criteria in all 3 valid runs; 5000: meets the criteria in all 3 valid runs; 7500: meets the criteria in all 3 valid runs; 9000: 0 valid of 3 runs; 10000: 0 valid of 5 runs
- **sliding_window**: **7,500 RPS** — 1000: meets the criteria in all 3 valid runs; 2500: meets the criteria in all 3 valid runs; 5000: meets the criteria in all 3 valid runs; 7500: meets the criteria in all 3 valid runs; 9000: 0 valid of 3 runs; 10000: 0 valid of 3 runs

### B1 load levels (mixed profile, Nginx)

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 1,000 | 3/2 | 1,000 (1,000–1,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 0.53 (0.52–0.54) | 0.86 (0.78–0.88) ⚠ | 1.63 (1.47–1.79) ⚠ | 42.6 (6.8–47.9) ⚠ | 0.152 (0.152–0.154) | 0.139 (0.138–0.143) | 0.205 (0.204–0.214) | 0.528 (0.502–0.535) | 0.135 (0.131–0.140) | 0.515 (0.489–0.525) | 14.0 (14.0–15.0) | 63.2 (59.1–63.2) |
| fixed_window @ 1,000 | 3/0 | 1,000 (1,000–1,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) ⚠ | 0.66 (0.53–0.66) ⚠ | 1.24 (0.81–1.25) ⚠ | 1.97 (1.93–2.04) | 38.3 (8.0–54.0) ⚠ | 0.180 (0.156–0.180) ⚠ | 0.167 (0.138–0.167) ⚠ | 0.306 (0.200–0.306) ⚠ | 0.553 (0.539–0.560) | 0.160 (0.135–0.160) ⚠ | 0.535 (0.529–0.544) | 13.1 (13.1–13.1) | 69.1 (57.1–72.2) ⚠ |
| sliding_window @ 1,000 | 3/0 | 1,000 (1,000–1,000) | 0.000 % (0.000 %–0.000 %) | 0.2 % (0.2 %–0.2 %) ⚠ | 0.54 (0.54–0.67) ⚠ | 0.76 (0.74–1.26) ⚠ | 1.55 (1.40–1.98) ⚠ | 10.1 (7.6–11.2) ⚠ | 0.152 (0.148–0.188) ⚠ | 0.143 (0.141–0.178) ⚠ | 0.203 (0.195–0.316) ⚠ | 0.472 (0.431–0.551) ⚠ | 0.138 (0.136–0.168) ⚠ | 0.461 (0.418–0.532) ⚠ | 16.1 (16.1–16.1) | 62.2 (60.2–75.3) ⚠ |
| token_bucket @ 2,500 | 3/0 | 2,500 (2,500–2,500) | 0.000 % (0.000 %–0.000 %) | 12.0 % (12.0 %–12.0 %) | 0.34 (0.33–0.35) | 0.65 (0.65–0.66) | 1.89 (1.82–1.89) | 9.1 (8.7–43.8) ⚠ | 0.110 (0.107–0.113) | 0.095 (0.092–0.096) | 0.184 (0.182–0.186) | 0.411 (0.398–0.426) | 0.087 (0.085–0.090) | 0.404 (0.390–0.419) | 8.0 (8.0–8.0) | 38.1 (37.1–38.1) |
| fixed_window @ 2,500 | 3/2 | 2,500 (2,500–2,500) | 0.000 % (0.000 %–0.000 %) | 12.0 % (12.0 %–12.0 %) | 0.34 (0.32–0.35) | 0.71 (0.66–0.73) ⚠ | 2.36 (2.06–3.09) ⚠ | 107.5 (78.7–119.8) ⚠ | 0.121 (0.112–0.139) ⚠ | 0.092 (0.086–0.095) | 0.196 (0.189–0.203) | 0.430 (0.380–0.610) ⚠ | 0.086 (0.083–0.087) | 0.420 (0.363–0.598) ⚠ | 7.0 (7.0–7.0) | 38.1 (37.1–39.2) |
| sliding_window @ 2,500 | 3/0 | 2,500 (2,500–2,500) | 0.000 % (0.000 %–0.000 %) | 12.0 % (12.0 %–12.3 %) | 0.33 (0.33–0.34) | 0.72 (0.63–0.74) ⚠ | 2.35 (1.89–2.92) ⚠ | 68.2 (22.3–105.9) ⚠ | 0.127 (0.108–0.137) ⚠ | 0.092 (0.092–0.093) | 0.205 (0.184–0.206) ⚠ | 0.486 (0.343–0.575) ⚠ | 0.086 (0.085–0.086) | 0.469 (0.338–0.560) ⚠ | 9.0 (9.0–9.0) | 44.0 (42.2–44.0) |
| token_bucket @ 5,000 | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.33 (0.33–0.33) | 1.45 (1.19–1.55) ⚠ | 6.67 (5.55–7.79) ⚠ | 66.6 (39.1–122.0) ⚠ | 0.137 (0.127–0.144) ⚠ | 0.089 (0.088–0.090) | 0.276 (0.244–0.282) ⚠ | 1.071 (0.780–1.133) ⚠ | 0.085 (0.085–0.086) | 1.049 (0.768–1.112) ⚠ | 7.0 (7.0–8.0) ⚠ | 44.0 (41.2–45.1) |
| fixed_window @ 5,000 | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.33 (0.32–0.33) | 1.45 (1.32–1.48) ⚠ | 6.25 (5.78–7.12) ⚠ | 111.2 (41.5–1,070.7) ⚠ | 0.131 (0.129–0.139) | 0.086 (0.085–0.088) | 0.257 (0.238–0.262) | 0.916 (0.878–0.925) | 0.083 (0.083–0.085) | 0.902 (0.862–0.909) | 6.0 (6.0–6.0) | 41.2 (40.2–41.2) |
| sliding_window @ 5,000 | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.33 (0.33–0.33) | 1.17 (1.11–1.22) | 5.64 (5.27–5.87) ⚠ | 62.4 (50.0–93.6) ⚠ | 0.130 (0.129–0.133) | 0.092 (0.091–0.092) | 0.251 (0.244–0.257) | 0.776 (0.774–0.824) | 0.086 (0.086–0.086) | 0.762 (0.762–0.809) | 8.0 (8.0–8.0) | 41.2 (39.2–42.2) |
| token_bucket @ 7,500 | 3/3 | 7,500 (7,500–7,500) | 0.000 % (0.000 %–0.000 %) | 17.3 % (17.3 %–17.3 %) | 0.43 (0.43–0.43) | 3.34 (3.30–3.56) | 12.29 (11.87–12.56) | 70.7 (69.5–72.9) | 0.175 (0.172–0.177) | 0.112 (0.111–0.112) | 0.426 (0.419–0.432) | 1.272 (1.148–1.278) ⚠ | 0.108 (0.108–0.109) | 1.257 (1.130–1.261) ⚠ | 7.0 (7.0–7.0) | 43.0 (43.0–43.0) |
| fixed_window @ 7,500 | 3/3 | 7,500 (7,500–7,500) | 0.000 % (0.000 %–0.000 %) | 17.3 % (17.3 %–17.3 %) | 0.51 (0.44–0.59) ⚠ | 5.64 (4.09–10.60) ⚠ | 20.41 (14.07–25.83) ⚠ | 106.6 (63.0–1,079.1) ⚠ | 0.190 (0.171–0.225) ⚠ | 0.128 (0.112–0.150) ⚠ | 0.472 (0.426–0.552) ⚠ | 1.289 (1.157–1.424) ⚠ | 0.123 (0.108–0.145) ⚠ | 1.274 (1.140–1.398) ⚠ | 6.0 (6.0–8.0) ⚠ | 39.2 (37.1–41.2) ⚠ |
| sliding_window @ 7,500 | 3/2 | 7,500 (7,500–7,500) | 0.000 % (0.000 %–0.000 %) | 17.3 % (17.3 %–17.4 %) | 0.62 (0.58–0.65) ⚠ | 11.59 (7.90–14.56) ⚠ | 30.05 (23.92–37.77) ⚠ | 109.6 (80.1–135.7) ⚠ | 0.241 (0.217–0.249) ⚠ | 0.160 (0.150–0.165) | 0.603 (0.532–0.642) ⚠ | 1.693 (1.356–1.761) ⚠ | 0.154 (0.145–0.160) | 1.675 (1.341–1.741) ⚠ | 9.0 (9.0–9.0) | 47.1 (46.1–48.1) |
| token_bucket @ 9,000 | 0/3 | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |
| fixed_window @ 9,000 | 0/3 | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |
| sliding_window @ 9,000 | 0/3 | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |
| token_bucket @ 10,000 | 0/3 | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |
| fixed_window @ 10,000 | 0/5 | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |
| sliding_window @ 10,000 | 0/3 | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – |

### B1 load levels (mixed profile, Nginx) — resources

CPU % from docker stats (100 % = one core; app limit 150 %, Redis 100 %, Nginx 100 %), JVM/Redis from Prometheus.

| configuration | app CPU avg % (max inst) | app CPU peak % | JVM process CPU | heap max MiB | GC pause ms | Tomcat busy max | Redis CPU % | Redis main-thread cores | Redis ops/s | Redis clients | Redis MiB | Nginx CPU % | k6 CPU % of limit (worst 5 s) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 1,000 | 13 (12–13) | 21 (20–22) | 0.08 (0.08–0.09) | 112 (112–113) | 1,253 (1,179–1,255) | 3 (3–3) | 5 (5–5) | 0.04 (0.04–0.05) | 5,009 (5,009–5,009) | 7 (7–7) | 1.8 (1.8–1.8) | 10 (9–10) | 13 (13–13) |
| fixed_window @ 1,000 | 15 (13–15) ⚠ | 18 (18–28) ⚠ | 0.10 (0.08–0.10) ⚠ | 120 (119–120) | 1,790 (1,753–1,805) | 3 (3–3) | 5 (4–5) | 0.05 (0.04–0.05) | 4,182 (4,159–4,182) | 7 (7–7) | 3.1 (3.1–3.1) | 11 (10–11) ⚠ | 14 (14–14) |
| sliding_window @ 1,000 | 12 (12–15) ⚠ | 15 (15–17) ⚠ | 0.08 (0.08–0.10) ⚠ | 120 (119–120) | 1,535 (1,511–1,698) ⚠ | 2 (2–3) ⚠ | 5 (5–5) | 0.05 (0.05–0.05) | 5,005 (5,005–5,005) | 7 (7–7) | 3.1 (3.1–3.2) | 10 (10–11) ⚠ | 13 (13–13) |
| token_bucket @ 2,500 | 19 (19–19) | 23 (22–24) | 0.13 (0.13–0.13) | 119 (119–120) | 2,166 (2,145–2,205) | 3 (3–4) ⚠ | 7 (7–7) | 0.06 (0.06–0.06) | 11,909 (11,909–11,909) | 7 (7–7) | 2.3 (2.2–2.3) | 16 (15–16) | 18 (18–19) |
| fixed_window @ 2,500 | 20 (20–21) | 46 (32–47) ⚠ | 0.13 (0.13–0.13) | 121 (120–121) | 2,271 (2,203–2,326) | 3 (3–3) | 6 (6–6) | 0.06 (0.06–0.06) | 9,888 (9,869–9,888) | 7 (7–7) | 3.1 (3.1–3.1) | 16 (16–16) | 26 (20–26) ⚠ |
| sliding_window @ 2,500 | 21 (20–21) | 35 (29–39) ⚠ | 0.13 (0.12–0.13) | 122 (121–122) | 2,254 (2,176–2,260) | 3 (3–4) ⚠ | 7 (7–7) | 0.07 (0.06–0.07) | 11,906 (11,893–11,907) | 7 (7–7) | 3.1 (3.1–3.1) | 16 (16–16) | 21 (19–23) ⚠ |
| token_bucket @ 5,000 | 38 (38–38) | 48 (46–59) ⚠ | 0.24 (0.24–0.25) | 125 (122–127) | 4,418 (4,300–4,435) | 10 (6–13) ⚠ | 12 (12–13) | 0.12 (0.12–0.12) | 23,409 (23,409–23,410) | 7 (7–7) | 2.9 (2.9–2.9) | 29 (29–30) | 33 (32–33) |
| fixed_window @ 5,000 | 38 (37–39) | 61 (54–84) ⚠ | 0.24 (0.24–0.25) | 128 (127–128) | 4,220 (4,159–4,380) | 6 (5–10) ⚠ | 12 (11–12) | 0.11 (0.11–0.11) | 19,388 (19,304–19,388) | 7 (7–7) | 3.1 (3.1–3.1) | 29 (29–30) | 33 (33–44) ⚠ |
| sliding_window @ 5,000 | 37 (36–37) | 45 (42–53) ⚠ | 0.24 (0.24–0.25) | 130 (115–130) ⚠ | 4,104 (3,840–4,147) | 8 (3–12) ⚠ | 12 (12–12) | 0.12 (0.12–0.12) | 23,406 (23,406–23,406) | 7 (7–7) | 3.2 (3.1–3.2) | 28 (28–28) | 31 (31–31) |
| token_bucket @ 7,500 | 60 (59–60) | 69 (67–73) | 0.38 (0.38–0.39) | 127 (123–129) | 5,345 (5,214–5,392) | 7 (6–7) ⚠ | 19 (19–19) | 0.18 (0.18–0.18) | 34,909 (34,909–34,910) | 7 (7–7) | 3.2 (3.1–3.2) | 44 (43–44) | 45 (45–46) |
| fixed_window @ 7,500 | 66 (61–76) ⚠ | 91 (68–92) ⚠ | 0.42 (0.39–0.49) ⚠ | 133 (132–134) | 5,729 (5,376–7,043) ⚠ | 9 (9–11) ⚠ | 19 (18–22) ⚠ | 0.19 (0.17–0.21) ⚠ | 28,888 (28,887–28,892) | 7 (7–7) | 3.1 (3.1–3.1) | 48 (44–54) ⚠ | 55 (47–61) ⚠ |
| sliding_window @ 7,500 | 78 (74–79) | 97 (90–97) | 0.49 (0.47–0.50) | 119 (113–139) ⚠ | 7,502 (7,088–7,622) | 13 (12–23) ⚠ | 24 (23–24) | 0.23 (0.22–0.23) | 34,905 (34,905–34,907) | 7 (7–7) | 3.2 (3.1–3.2) | 54 (53–55) | 59 (59–61) |
| token_bucket @ 9,000 | – | – | – | – | – | – | – | – | – | – | – | – | – |
| fixed_window @ 9,000 | – | – | – | – | – | – | – | – | – | – | – | – | – |
| sliding_window @ 9,000 | – | – | – | – | – | – | – | – | – | – | – | – | – |
| token_bucket @ 10,000 | – | – | – | – | – | – | – | – | – | – | – | – | – |
| fixed_window @ 10,000 | – | – | – | – | – | – | – | – | – | – | – | – | – |
| sliding_window @ 10,000 | – | – | – | – | – | – | – | – | – | – | – | – | – |

### B2 stress ramp and knee

##### 20261111T175303Z-b2-token_bucket-15000-r1 (run-level validity: VALID)

Knee (highest consecutive step meeting every criterion from the first step): **11000**

| step RPS | achieved/target | non-429 errors | E2E p99 ms | limiter p99 ms | app process CPU (max) | Redis CPU cores | k6 CPU % limit | meets criteria |
|---|---|---|---|---|---|---|---|---|
| 1000 | 1.000 | 0.0000% | 1.19 | 0.468 | 0.07 | 0.04 | 15 | yes |
| 2000 | 0.992 | 0.0000% | 1.99 | 0.423 | 0.11 | 0.06 | 19 | yes |
| 3000 | 0.994 | 0.0000% | 2.02 | 0.364 | 0.13 | 0.07 | 23 | yes |
| 4000 | 0.996 | 0.0000% | 2.82 | 0.421 | 0.18 | 0.10 | 29 | yes |
| 5000 | 0.997 | 0.0000% | 5.69 | 0.610 | 0.24 | 0.12 | 37 | yes |
| 6000 | 0.997 | 0.0000% | 5.70 | 0.614 | 0.29 | 0.15 | 44 | yes |
| 7000 | 0.998 | 0.0000% | 7.83 | 0.590 | 0.36 | 0.18 | 51 | yes |
| 8000 | 0.998 | 0.0000% | 11.83 | 0.835 | 0.42 | 0.20 | 59 | yes |
| 9000 | 0.998 | 0.0000% | 14.54 | 1.897 | 0.50 | 0.24 | 67 | yes |
| 10000 | 0.997 | 0.0000% | 27.73 | 3.083 | 0.60 | 0.27 | 79 | yes |
| 11000 | 0.998 | 0.0000% | 31.44 | 3.904 | 0.68 | 0.30 | 84 | yes |
| 12000 | 0.998 | 0.0000% | 52.36 | 7.232 | 0.76 | 0.31 | 98 | no |
| 13000 | 0.943 | 0.0000% | 52.14 | 9.169 | 0.79 | 0.31 | 98 | no |
| 14000 | 0.820 | 0.0000% | 60.83 | 8.365 | 0.75 | 0.30 | 99 | no |
| 15000 | 0.661 | 0.0000% | 53.79 | 6.411 | 0.66 | 0.27 | 99 | no |

##### 20261111T180231Z-b2-token_bucket-15000-r2 (run-level validity: VALID)

Knee (highest consecutive step meeting every criterion from the first step): **11000**

| step RPS | achieved/target | non-429 errors | E2E p99 ms | limiter p99 ms | app process CPU (max) | Redis CPU cores | k6 CPU % limit | meets criteria |
|---|---|---|---|---|---|---|---|---|
| 1000 | 1.000 | 0.0000% | 2.02 | 0.629 | 0.10 | 0.05 | 16 | yes |
| 2000 | 0.992 | 0.0000% | 2.79 | 0.518 | 0.11 | 0.06 | 19 | yes |
| 3000 | 0.994 | 0.0000% | 2.06 | 0.344 | 0.14 | 0.07 | 24 | yes |
| 4000 | 0.996 | 0.0000% | 6.09 | 0.953 | 0.18 | 0.10 | 32 | yes |
| 5000 | 0.997 | 0.0000% | 5.53 | 0.561 | 0.24 | 0.12 | 36 | yes |
| 6000 | 0.997 | 0.0000% | 8.06 | 0.589 | 0.29 | 0.15 | 43 | yes |
| 7000 | 0.998 | 0.0000% | 11.77 | 0.630 | 0.35 | 0.18 | 52 | yes |
| 8000 | 0.998 | 0.0000% | 12.78 | 0.805 | 0.41 | 0.20 | 59 | yes |
| 9000 | 0.997 | 0.0000% | 16.25 | 1.876 | 0.49 | 0.24 | 68 | yes |
| 10000 | 0.999 | 0.0000% | 21.12 | 3.217 | 0.59 | 0.27 | 77 | yes |
| 11000 | 0.999 | 0.0000% | 24.38 | 3.836 | 0.67 | 0.29 | 83 | yes |
| 12000 | 0.997 | 0.0000% | 37.20 | 7.526 | 0.75 | 0.31 | 93 | no |
| 13000 | 0.928 | 0.0000% | 48.46 | 10.091 | 0.78 | 0.31 | 99 | no |
| 14000 | 0.780 | 0.0000% | 46.17 | 8.150 | 0.76 | 0.30 | 100 | no |
| 15000 | 0.621 | 0.0000% | 66.38 | 10.369 | 0.71 | 0.27 | 98 | no |

##### 20261111T181201Z-b2-token_bucket-15000-r3 (run-level validity: VALID)

Knee (highest consecutive step meeting every criterion from the first step): **11000**

| step RPS | achieved/target | non-429 errors | E2E p99 ms | limiter p99 ms | app process CPU (max) | Redis CPU cores | k6 CPU % limit | meets criteria |
|---|---|---|---|---|---|---|---|---|
| 1000 | 1.000 | 0.0000% | 1.74 | 0.582 | 0.08 | 0.05 | 15 | yes |
| 2000 | 0.991 | 0.0000% | 1.92 | 0.429 | 0.11 | 0.06 | 20 | yes |
| 3000 | 0.994 | 0.0000% | 1.91 | 0.409 | 0.13 | 0.07 | 24 | yes |
| 4000 | 0.996 | 0.0000% | 3.45 | 0.597 | 0.18 | 0.10 | 31 | yes |
| 5000 | 0.997 | 0.0000% | 5.41 | 0.688 | 0.23 | 0.12 | 38 | yes |
| 6000 | 0.997 | 0.0000% | 8.20 | 0.665 | 0.29 | 0.15 | 45 | yes |
| 7000 | 0.998 | 0.0000% | 9.19 | 0.818 | 0.36 | 0.18 | 52 | yes |
| 8000 | 0.998 | 0.0000% | 13.58 | 1.027 | 0.42 | 0.21 | 62 | yes |
| 9000 | 0.998 | 0.0000% | 16.66 | 2.391 | 0.52 | 0.25 | 70 | yes |
| 10000 | 0.998 | 0.0000% | 21.75 | 3.747 | 0.62 | 0.28 | 79 | yes |
| 11000 | 0.998 | 0.0000% | 29.71 | 7.405 | 0.70 | 0.29 | 84 | yes |
| 12000 | 0.992 | 0.0000% | 71.41 | 16.258 | 0.81 | 0.31 | 98 | no |
| 13000 | 0.859 | 0.0000% | 69.33 | 13.917 | 0.78 | 0.30 | 98 | no |
| 14000 | 0.773 | 0.0000% | 58.84 | 11.325 | 0.76 | 0.30 | 98 | no |
| 15000 | 0.670 | 0.0000% | 54.90 | 9.466 | 0.72 | 0.28 | 98 | no |

### B3 limiter overhead A/B (token bucket; limiter on vs off; medians, ms)

| RPS | valid on/off | E2E p50 on vs off | E2E p95 on vs off | E2E p99 on vs off | in-process limiter histogram (on) |
|---|---|---|---|---|---|
| 1000 | 3/3 | 0.65 (0.55–0.66) ⚠ vs 0.62 (0.46–0.62) ⚠ (Δ 0.031) | 1.21 (0.76–1.44) ⚠ vs 1.03 (0.88–1.05) ⚠ (Δ 0.183) | 1.84 (1.36–2.52) ⚠ vs 1.88 (1.49–1.90) ⚠ (Δ -0.035) | p50 0.171 (0.146–0.174) ⚠ / p95 0.308 (0.205–0.349) ⚠ / p99 0.595 (0.485–0.726) ⚠ |
| 5000 | 3/3 | 0.35 (0.34–0.35) vs 0.22 (0.21–0.22) (Δ 0.130) | 1.25 (1.19–1.45) ⚠ vs 0.70 (0.67–0.70) (Δ 0.558) | 6.56 (5.89–7.28) ⚠ vs 4.28 (3.88–5.50) ⚠ (Δ 2.285) | p50 0.095 (0.095–0.096) / p95 0.270 (0.256–0.283) ⚠ / p99 0.947 (0.852–1.034) ⚠ |

### B0 baseline (100 RPS)

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 100 | 3/0 | 100 (100–100) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 2.50 (2.47–2.50) | 3.37 (3.35–3.38) | 4.36 (4.27–4.62) | 38.4 (19.4–40.8) ⚠ | 0.504 (0.499–0.505) | 0.513 (0.504–0.515) | 0.778 (0.774–0.779) | 1.301 (1.289–1.331) | 0.464 (0.458–0.464) | 1.247 (1.227–1.277) | 81.4 (78.3–81.4) | 200.7 (199.7–202.8) |
| fixed_window @ 100 | 3/0 | 100 (100–100) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 2.38 (2.37–2.49) | 3.34 (2.89–3.34) ⚠ | 4.15 (3.56–4.87) ⚠ | 18.6 (14.8–70.0) ⚠ | 0.497 (0.455–0.503) | 0.485 (0.465–0.513) | 0.773 (0.641–0.776) ⚠ | 1.232 (1.135–1.278) ⚠ | 0.443 (0.432–0.460) | 1.152 (1.081–1.200) ⚠ | 79.4 (56.1–82.4) ⚠ | 197.6 (183.3–200.7) |
| sliding_window @ 100 | 3/0 | 100 (100–100) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 2.52 (2.38–2.55) | 3.31 (2.95–3.35) ⚠ | 4.06 (3.84–4.42) ⚠ | 20.4 (15.4–25.7) ⚠ | 0.510 (0.461–0.513) ⚠ | 0.523 (0.467–0.532) ⚠ | 0.773 (0.661–0.777) ⚠ | 1.219 (1.193–1.261) | 0.466 (0.431–0.476) | 1.146 (1.096–1.183) | 85.5 (60.2–86.0) ⚠ | 209.9 (193.5–210.9) |

### B4 headroom vs throttled (5,000 RPS)

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket headroom | 3/4 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 0.33 (0.32–0.34) | 1.03 (1.03–1.09) | 5.04 (4.95–6.00) ⚠ | 40.6 (25.4–77.5) ⚠ | 0.128 (0.127–0.135) | 0.091 (0.086–0.092) | 0.245 (0.240–0.247) | 0.750 (0.722–0.944) ⚠ | 0.086 (0.084–0.086) | 0.736 (0.708–0.929) ⚠ | 7.0 (7.0–8.0) ⚠ | 39.2 (39.2–47.1) ⚠ |
| fixed_window headroom | 3/3 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 0.34 (0.32–0.48) ⚠ | 1.06 (1.03–3.36) ⚠ | 5.44 (4.95–14.04) ⚠ | 82.2 (47.6–106.5) ⚠ | 0.129 (0.124–0.208) ⚠ | 0.088 (0.085–0.120) ⚠ | 0.236 (0.222–0.616) ⚠ | 0.704 (0.690–1.615) ⚠ | 0.085 (0.083–0.115) ⚠ | 0.691 (0.678–1.586) ⚠ | 6.0 (6.0–8.0) ⚠ | 38.1 (34.0–49.2) ⚠ |
| sliding_window headroom | 3/1 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 0.33 (0.33–0.34) | 1.15 (1.03–1.19) ⚠ | 5.65 (4.87–6.74) ⚠ | 35.4 (19.4–67.9) ⚠ | 0.128 (0.125–0.130) | 0.091 (0.090–0.092) | 0.242 (0.238–0.251) | 0.776 (0.697–0.802) ⚠ | 0.086 (0.085–0.086) | 0.761 (0.685–0.788) ⚠ | 8.0 (8.0–8.0) | 39.2 (39.2–40.2) |
| token_bucket throttled | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 96.0 % (96.0 %–96.0 %) | 0.29 (0.28–0.29) | 0.85 (0.81–0.97) ⚠ | 4.77 (4.44–5.34) ⚠ | 45.6 (19.3–76.3) ⚠ | 0.113 (0.112–0.119) | 0.084 (0.084–0.085) | 0.213 (0.211–0.216) | 0.516 (0.512–0.567) ⚠ | 0.083 (0.083–0.083) | 0.507 (0.503–0.556) ⚠ | 7.0 (7.0–7.0) | 32.1 (32.1–32.1) |
| fixed_window throttled | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 96.0 % (96.0 %–96.0 %) | 0.29 (0.28–0.30) | 0.88 (0.81–0.91) ⚠ | 4.80 (4.44–4.98) ⚠ | 45.0 (30.9–46.6) ⚠ | 0.113 (0.109–0.115) | 0.084 (0.083–0.086) | 0.211 (0.205–0.213) | 0.511 (0.475–0.528) ⚠ | 0.083 (0.082–0.084) | 0.503 (0.465–0.518) ⚠ | 5.0 (5.0–6.0) ⚠ | 30.1 (30.1–30.1) |
| sliding_window throttled | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 96.0 % (96.0 %–96.0 %) | 0.30 (0.30–0.30) | 0.98 (0.86–1.00) ⚠ | 5.61 (4.84–6.40) ⚠ | 59.1 (41.1–84.0) ⚠ | 0.120 (0.118–0.124) | 0.087 (0.087–0.087) | 0.224 (0.219–0.227) | 0.579 (0.557–0.623) ⚠ | 0.085 (0.085–0.085) | 0.569 (0.545–0.610) ⚠ | 7.0 (7.0–7.0) | 33.0 (33.0–34.0) |

### B5 burst (10,000 requests in 1 s, one fresh user)

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 10,000 | 3/1 | 6,435 (6,402–6,537) | 0.000 % (0.000 %–0.000 %) | 98.8 % (98.8 %–98.8 %) | 0.84 (0.73–0.90) ⚠ | 50.29 (48.45–52.07) | 94.57 (73.82–126.81) ⚠ | 140.7 (130.7–143.7) | – | – | – | – | – | – | 14.0 (14.0–15.0) | 70.1 (54.0–72.2) ⚠ |
| fixed_window @ 10,000 | 3/0 | 5,557 (4,155–6,040) ⚠ | 0.000 % (0.000 %–0.000 %) | 98.8 % (98.8 %–98.8 %) | 0.71 (0.66–0.78) ⚠ | 65.76 (56.81–71.82) ⚠ | 129.61 (125.07–148.62) ⚠ | 1,053.8 (151.2–1,068.9) ⚠ | – | – | – | – | – | – | 12.0 (12.0–13.1) | 52.2 (52.2–68.1) ⚠ |
| sliding_window @ 10,000 | 3/0 | 6,182 (6,004–6,216) | 0.000 % (0.000 %–0.000 %) | 98.8 % (98.8 %–98.8 %) | 0.70 (0.68–0.84) ⚠ | 60.96 (48.75–61.65) ⚠ | 133.43 (71.07–134.06) ⚠ | 147.6 (128.9–158.0) ⚠ | – | – | – | – | – | – | 16.1 (14.0–16.1) ⚠ | 57.1 (55.0–76.3) ⚠ |

### B6 concurrency

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 1,000 | 3/3 | 1,275 (594–1,383) ⚠ | 0.000 % (0.000 %–0.000 %) | 90.0 % (90.0 %–90.0 %) | 30.51 (27.52–31.17) ⚠ | 41.48 (41.45–45.84) ⚠ | 46.16 (44.73–1,049.12) ⚠ | 47.9 (46.2–1,052.5) ⚠ | – | – | – | – | – | – | 8.0 (7.0–8.0) ⚠ | 60.2 (57.1–60.2) |

### B7 distributed: Nginx vs direct (5,000 RPS)

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket via direct | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.26 (0.26–0.26) | 1.79 (1.50–1.91) ⚠ | 7.77 (6.69–8.18) ⚠ | 122.6 (121.5–165.7) ⚠ | 0.127 (0.120–0.128) | 0.086 (0.085–0.086) | 0.241 (0.231–0.245) | 0.752 (0.664–0.759) ⚠ | 0.084 (0.083–0.085) | 0.741 (0.654–0.748) ⚠ | 7.0 (7.0–7.0) | 34.0 (34.0–35.1) |
| fixed_window via direct | 3/0 | 5,000 (4,999–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.26 (0.26–0.26) | 1.40 (1.32–1.49) ⚠ | 6.54 (6.21–6.80) | 112.5 (93.8–113.9) ⚠ | 0.118 (0.117–0.120) | 0.085 (0.084–0.085) | 0.222 (0.218–0.228) | 0.610 (0.597–0.645) | 0.083 (0.083–0.084) | 0.601 (0.588–0.635) | 6.0 (6.0–6.0) | 31.1 (31.1–31.1) |
| sliding_window via direct | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.25 (0.25–0.26) | 1.29 (1.24–1.45) ⚠ | 6.39 (5.70–6.80) ⚠ | 89.6 (68.6–105.8) ⚠ | 0.118 (0.117–0.124) | 0.085 (0.085–0.086) | 0.223 (0.221–0.232) | 0.600 (0.596–0.692) ⚠ | 0.084 (0.083–0.084) | 0.592 (0.589–0.683) ⚠ | 8.0 (8.0–8.0) | 35.1 (34.0–35.1) |
| token_bucket via nginx | 3/1 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.33 (0.33–0.34) | 1.32 (1.15–1.34) ⚠ | 6.82 (6.73–7.02) | 56.4 (51.7–59.5) ⚠ | 0.135 (0.129–0.137) | 0.091 (0.090–0.092) | 0.259 (0.245–0.262) | 0.883 (0.783–0.901) ⚠ | 0.086 (0.086–0.087) | 0.869 (0.772–0.887) ⚠ | 7.0 (7.0–7.0) | 38.1 (38.1–38.1) |
| fixed_window via nginx | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.33 (0.33–0.34) | 1.11 (1.11–1.15) | 6.31 (5.39–6.44) ⚠ | 61.2 (52.0–63.7) ⚠ | 0.125 (0.122–0.128) | 0.088 (0.087–0.090) | 0.236 (0.234–0.239) | 0.746 (0.668–0.776) ⚠ | 0.085 (0.085–0.086) | 0.732 (0.655–0.763) ⚠ | 6.0 (6.0–6.0) | 34.0 (34.0–34.0) |
| sliding_window via nginx | 3/1 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.33 (0.33–0.33) | 1.24 (1.13–2.38) ⚠ | 5.58 (5.16–17.37) ⚠ | 81.3 (76.6–1,065.0) ⚠ | 0.132 (0.129–0.212) ⚠ | 0.092 (0.091–0.093) | 0.259 (0.245–0.329) ⚠ | 0.842 (0.743–1.904) ⚠ | 0.086 (0.086–0.087) | 0.828 (0.729–1.873) ⚠ | 8.0 (8.0–8.0) | 43.0 (39.2–44.0) ⚠ |

### B8 hot key

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 2,000 | 3/0 | 2,000 (2,000–2,000) | 0.000 % (0.000 %–0.000 %) | 99.9 % (99.9 %–99.9 %) | 0.40 (0.34–0.40) ⚠ | 0.66 (0.58–0.67) ⚠ | 1.43 (1.39–1.50) | 15.1 (10.3–65.4) ⚠ | 0.133 (0.115–0.137) ⚠ | 0.121 (0.103–0.121) ⚠ | 0.208 (0.179–0.211) ⚠ | 0.389 (0.360–0.391) | 0.114 (0.100–0.115) ⚠ | 0.382 (0.349–0.383) | 9.0 (9.0–9.0) | 38.1 (35.1–38.1) |
| fixed_window @ 2,000 | 3/0 | 2,000 (2,000–2,000) | 0.000 % (0.000 %–0.000 %) | 99.9 % (99.9 %–99.9 %) | 0.36 (0.33–0.39) ⚠ | 0.64 (0.58–0.68) ⚠ | 1.55 (1.49–1.66) ⚠ | 13.7 (7.4–49.7) ⚠ | 0.121 (0.113–0.131) ⚠ | 0.107 (0.102–0.117) ⚠ | 0.196 (0.177–0.210) ⚠ | 0.382 (0.358–0.396) ⚠ | 0.103 (0.099–0.108) | 0.375 (0.350–0.388) ⚠ | 7.0 (7.0–8.0) ⚠ | 35.1 (33.0–37.1) ⚠ |
| sliding_window @ 2,000 | 3/0 | 2,000 (2,000–2,000) | 0.000 % (0.000 %–0.000 %) | 99.9 % (99.9 %–99.9 %) | 0.38 (0.34–0.40) ⚠ | 0.64 (0.58–0.64) | 1.44 (1.41–1.51) | 9.1 (8.6–14.0) ⚠ | 0.123 (0.117–0.131) ⚠ | 0.114 (0.103–0.121) ⚠ | 0.192 (0.183–0.203) ⚠ | 0.381 (0.372–0.399) | 0.105 (0.101–0.114) ⚠ | 0.371 (0.363–0.391) | 9.0 (9.0–10.0) ⚠ | 37.1 (36.1–39.2) |
| token_bucket @ 5,000 | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 100.0 % (100.0 %–100.0 %) | 0.28 (0.28–0.29) | 1.16 (0.77–1.39) ⚠ | 5.96 (4.10–7.42) ⚠ | 93.5 (29.1–103.6) ⚠ | 0.125 (0.113–0.132) ⚠ | 0.084 (0.084–0.085) | 0.225 (0.212–0.249) ⚠ | 0.747 (0.524–0.930) ⚠ | 0.083 (0.082–0.083) | 0.733 (0.514–0.914) ⚠ | 6.0 (6.0–7.0) ⚠ | 37.1 (31.1–38.1) ⚠ |
| fixed_window @ 5,000 | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 100.0 % (100.0 %–100.0 %) | 0.28 (0.28–0.29) | 0.77 (0.73–1.00) ⚠ | 4.22 (4.03–5.95) ⚠ | 57.2 (18.1–108.9) ⚠ | 0.110 (0.110–0.124) ⚠ | 0.084 (0.083–0.084) | 0.205 (0.204–0.216) | 0.484 (0.475–0.647) ⚠ | 0.082 (0.082–0.083) | 0.473 (0.463–0.634) ⚠ | 5.0 (5.0–6.0) ⚠ | 29.1 (29.1–31.1) |
| sliding_window @ 5,000 | 3/1 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 100.0 % (100.0 %–100.0 %) | 0.29 (0.29–0.30) | 0.77 (0.74–0.89) ⚠ | 4.65 (4.32–5.90) ⚠ | 52.9 (28.9–61.5) ⚠ | 0.114 (0.113–0.120) | 0.085 (0.085–0.087) | 0.210 (0.208–0.222) | 0.500 (0.480–0.578) ⚠ | 0.083 (0.083–0.085) | 0.489 (0.469–0.566) ⚠ | 7.0 (6.0–7.0) ⚠ | 31.1 (31.1–33.0) |

### B9 Redis pooling

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1,000 default | 3/0 | 1,000 (1,000–1,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 0.65 (0.53–0.66) ⚠ | 1.23 (0.75–1.26) ⚠ | 1.91 (1.32–2.02) ⚠ | 7.1 (6.7–11.1) ⚠ | 0.182 (0.148–0.185) ⚠ | 0.169 (0.140–0.173) ⚠ | 0.310 (0.197–0.314) ⚠ | 0.570 (0.464–0.587) ⚠ | 0.162 (0.137–0.165) ⚠ | 0.553 (0.452–0.568) ⚠ | 15.0 (14.0–15.0) | 72.2 (54.0–73.2) ⚠ |
| 1,000 pool-16 | 3/0 | 1,000 (1,000–1,000) | 0.000 % (0.000 %–0.000 %) | 0.0 % (0.0 %–0.0 %) | 0.54 (0.54–0.55) | 0.76 (0.74–0.76) | 1.43 (1.42–1.48) | 12.9 (10.8–105.2) ⚠ | 0.149 (0.148–0.151) | 0.141 (0.141–0.143) | 0.196 (0.195–0.201) | 0.485 (0.460–0.495) | 0.136 (0.136–0.137) | 0.473 (0.451–0.486) | 14.0 (14.0–14.0) | 53.2 (53.2–53.2) |
| 5,000 default | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.33 (0.33–0.34) | 1.10 (1.06–1.14) | 5.30 (5.19–5.50) | 37.4 (25.5–39.1) ⚠ | 0.128 (0.126–0.129) | 0.090 (0.089–0.092) | 0.246 (0.240–0.248) | 0.758 (0.714–0.766) | 0.086 (0.086–0.086) | 0.747 (0.702–0.755) | 7.0 (7.0–7.0) | 37.1 (37.1–38.1) |
| 5,000 pool-16 | 3/0 | 5,000 (5,000–5,000) | 0.000 % (0.000 %–0.000 %) | 16.0 % (16.0 %–16.0 %) | 0.34 (0.34–0.34) | 1.17 (1.14–1.19) | 5.77 (5.70–6.32) ⚠ | 47.6 (41.7–50.6) ⚠ | 0.138 (0.134–0.140) | 0.096 (0.095–0.097) | 0.261 (0.256–0.262) | 0.948 (0.883–0.976) | 0.090 (0.088–0.091) | 0.932 (0.869–0.960) | 7.0 (7.0–7.0) | 38.1 (37.1–38.1) |
| 7,500 default | 3/0 | 7,500 (7,500–7,500) | 0.000 % (0.000 %–0.000 %) | 17.3 % (17.3 %–17.3 %) | 0.45 (0.45–0.46) | 5.97 (5.59–6.05) | 15.64 (14.79–16.37) ⚠ | 98.2 (97.5–1,071.5) ⚠ | 0.184 (0.183–0.188) | 0.117 (0.116–0.117) | 0.458 (0.453–0.474) | 1.362 (1.317–1.412) | 0.112 (0.111–0.112) | 1.347 (1.303–1.391) | 7.0 (7.0–7.0) | 43.0 (43.0–44.0) |
| 7,500 pool-16 | 3/2 | 7,500 (7,500–7,500) | 0.000 % (0.000 %–0.000 %) | 17.3 % (17.3 %–17.3 %) | 0.47 (0.47–0.47) | 5.92 (5.92–7.74) ⚠ | 16.81 (16.73–21.44) ⚠ | 1,028.4 (89.9–1,066.8) ⚠ | 0.207 (0.203–0.209) | 0.121 (0.121–0.122) | 0.525 (0.521–0.536) | 1.715 (1.685–1.834) | 0.117 (0.117–0.118) | 1.698 (1.666–1.811) | 7.0 (7.0–7.0) | 44.0 (44.0–45.1) |

### B10 correctness under load (throttled, 2,000 RPS)

Latency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).

| configuration | valid/invalid | achieved RPS | non-429 errors | 429 share | E2E p50 | E2E p95 | E2E p99 | E2E max | limiter avg | limiter p50 | limiter p95 | limiter p99 | Redis RTT p50 | Redis RTT p99 | Redis server p50 µs | Redis server p99 µs |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| token_bucket @ 2,000 | 3/0 | 2,000 (2,000–2,000) | 0.000 % (0.000 %–0.000 %) | 90.0 % (90.0 %–90.0 %) | 0.37 (0.35–0.37) | 0.70 (0.66–0.71) | 2.56 (1.69–2.66) ⚠ | 138.3 (25.6–167.5) ⚠ | 0.135 (0.124–0.146) ⚠ | 0.107 (0.103–0.109) | 0.208 (0.196–0.208) | 0.489 (0.405–0.521) ⚠ | 0.103 (0.101–0.104) | 0.475 (0.397–0.509) ⚠ | 9.0 (9.0–9.0) | 41.2 (41.2–42.2) |
| fixed_window @ 2,000 | 3/0 | 2,000 (2,000–2,000) | 0.000 % (0.000 %–0.000 %) | 90.0 % (90.0 %–90.0 %) | 0.36 (0.31–0.39) ⚠ | 0.67 (0.66–0.86) ⚠ | 1.85 (1.62–4.99) ⚠ | 80.4 (56.6–116.5) ⚠ | 0.127 (0.123–0.156) ⚠ | 0.104 (0.095–0.114) ⚠ | 0.202 (0.194–0.224) ⚠ | 0.412 (0.401–1.019) ⚠ | 0.099 (0.087–0.106) ⚠ | 0.402 (0.393–0.996) ⚠ | 7.0 (7.0–8.0) ⚠ | 39.2 (38.1–39.2) |
| sliding_window @ 2,000 | 3/0 | 2,000 (2,000–2,000) | 0.000 % (0.000 %–0.000 %) | 90.1 % (90.1 %–90.1 %) | 0.39 (0.37–0.40) | 0.68 (0.66–0.69) | 1.57 (1.49–1.59) | 19.6 (11.1–25.6) ⚠ | 0.130 (0.124–0.134) | 0.118 (0.110–0.121) | 0.208 (0.202–0.213) | 0.403 (0.385–0.404) | 0.111 (0.105–0.115) | 0.395 (0.377–0.396) | 9.0 (9.0–10.0) ⚠ | 42.2 (41.2–43.0) |

### Correctness (B5 burst bound, B6 exactly 100 of 1,000, B10 per-user bounds)

| run | run validity | correctness | detail |
|---|---|---|---|
| 20261111T060049Z-b6-token_bucket-1000-r1 | INVALID | PASS | accepted 100 / rejected 900 / unexpected 0 |
| 20261111T060135Z-b6-token_bucket-1000-r2 | INVALID | PASS | accepted 100 / rejected 900 / unexpected 0 |
| 20261111T060221Z-b6-token_bucket-1000-r3 | INVALID | PASS | accepted 100 / rejected 900 / unexpected 0 |
| 20261111T060305Z-b6-token_bucket-1000-r4 | valid | PASS | accepted 100 / rejected 900 / unexpected 0 |
| 20261111T060349Z-b6-token_bucket-1000-r5 | valid | PASS | accepted 100 / rejected 900 / unexpected 0 |
| 20261111T060433Z-b5-token_bucket-10000-r1 | INVALID | PASS | users 1, accepted 122 vs bound 125, over-admitted 0 requests (0 users), min utilisation 0.98 |
| 20261111T060620Z-b5-token_bucket-10000-r2 | valid | PASS | users 1, accepted 121 vs bound 125, over-admitted 0 requests (0 users), min utilisation 0.97 |
| 20261111T060805Z-b5-token_bucket-10000-r3 | valid | PASS | users 1, accepted 121 vs bound 125, over-admitted 0 requests (0 users), min utilisation 0.97 |
| 20261111T060951Z-b5-token_bucket-10000-r4 | valid | PASS | users 1, accepted 121 vs bound 125, over-admitted 0 requests (0 users), min utilisation 0.97 |
| 20261111T061136Z-b5-fixed_window-10000-r1 | valid | PASS | users 1, accepted 120 vs bound 120, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T061323Z-b5-fixed_window-10000-r2 | valid | PASS | users 1, accepted 120 vs bound 120, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T061509Z-b5-fixed_window-10000-r3 | valid | PASS | users 1, accepted 120 vs bound 120, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T061655Z-b5-sliding_window-10000-r1 | valid | PASS | users 1, accepted 120 vs bound 120, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T061842Z-b5-sliding_window-10000-r2 | valid | PASS | users 1, accepted 120 vs bound 120, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T062028Z-b5-sliding_window-10000-r3 | valid | PASS | users 1, accepted 120 vs bound 120, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T062214Z-b10-token_bucket-2000-r1 | valid | PASS | users 100, accepted 59900 vs bound 60300, over-admitted 0 requests (0 users), min utilisation 0.99 |
| 20261111T062701Z-b10-token_bucket-2000-r2 | valid | PASS | users 100, accepted 59900 vs bound 60300, over-admitted 0 requests (0 users), min utilisation 0.99 |
| 20261111T063148Z-b10-token_bucket-2000-r3 | valid | PASS | users 100, accepted 59900 vs bound 60300, over-admitted 0 requests (0 users), min utilisation 0.99 |
| 20261111T063635Z-b10-fixed_window-2000-r1 | valid | PASS | users 100, accepted 54498 vs bound 60000, over-admitted 0 requests (0 users), min utilisation 0.91 |
| 20261111T064122Z-b10-fixed_window-2000-r2 | valid | PASS | users 100, accepted 60000 vs bound 60000, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T064608Z-b10-fixed_window-2000-r3 | valid | PASS | users 100, accepted 60000 vs bound 60000, over-admitted 0 requests (0 users), min utilisation 1.00 |
| 20261111T065055Z-b10-sliding_window-2000-r1 | valid | PASS | users 100, accepted 52500 vs bound 60000, over-admitted 0 requests (0 users), min utilisation 0.88 |
| 20261111T065543Z-b10-sliding_window-2000-r2 | valid | PASS | users 100, accepted 49921 vs bound 60000, over-admitted 0 requests (0 users), min utilisation 0.83 |
| 20261111T070030Z-b10-sliding_window-2000-r3 | valid | PASS | users 100, accepted 51233 vs bound 60000, over-admitted 0 requests (0 users), min utilisation 0.85 |
| 20261111T111200Z-b6-token_bucket-1000-r6 | valid | PASS | accepted 100 / rejected 900 / unexpected 0 |

### Redis memory per rate-limit key (MEMORY USAGE samples, valid B1 runs)

| algorithm | samples | min bytes | median bytes | max bytes |
|---|---|---|---|---|
| fixed_window | 40 | 104 | 104.0 | 104 |
| sliding_window | 60 | 112 | 112.0 | 112 |
| token_bucket | 45 | 112 | 112 | 112 |

### Targets (requirements) vs measured

| metric | target | measured |
|---|---|---|
| throughput | 5,000 RPS (3 instances) | token_bucket: 7500; fixed_window: 7500; sliding_window: 7500 |
| limiter overhead avg / P95 / P99 | < 5 / 10 / 20 ms | see B1 tables (in-process histogram, per run) |
| enforcement accuracy | zero over-admission | see the correctness table (B5, B6, B10) |
| non-429 error rate | < 0.1 % | see the error columns; invalid runs listed separately |
| load levels | 1,000 → 10,000 RPS | 10,000 RPS reported as achieved only where a valid run shows it |


## Invalid runs

All invalid runs, each with its reasons, are listed in the last section of
`benchmarks/reports/phase-3b-final/report.md`; their artifacts stay in `benchmarks/results/`.

## Reproduce

See `14-deployment-guide.md` §5d (running the benchmarks) and `benchmarks/reports/phase-3b-final/analysis.md` §10.
