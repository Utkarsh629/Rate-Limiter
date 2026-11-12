#!/usr/bin/env python3
"""Builds docs/11-performance-benchmarks.md from the generated report.md (3B-AC4: every number in docs/11 comes from
report.py output). The narrative below contains no measurement; result tables are copied verbatim from report.md by
section heading.

  python3 scripts/bench/report.py --out benchmarks/reports/phase-3b-final
  python3 benchmarks/reports/phase-3b-final/build_docs11.py
"""
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[2]
REPORT = (HERE / "report.md").read_text()


def section(heading):
    """The block from `heading` (exact line) up to the next heading of the same or a higher level."""
    lines = REPORT.splitlines()
    level = len(heading) - len(heading.lstrip("#"))
    start = lines.index(heading)
    out = [lines[start]]
    for line in lines[start + 1:]:
        m = re.match(r"^(#+) ", line)
        if m and len(m.group(1)) <= level:
            break
        out.append(line)
    return "\n".join(out).strip()


def demote(block, by=1):
    return re.sub(r"^(#+) ", lambda m: "#" * (len(m.group(1)) + by) + " ", block, flags=re.M)


HEAD = """# 11 — Performance Benchmarks

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
"""

PARTS = [
    "## B1 load levels: highest validated level, and maximum sustainable throughput",
    "### B1 load levels (mixed profile, Nginx)",
    "### B1 load levels (mixed profile, Nginx) — resources",
    "## B2 stress ramp and knee",
    "## B3 limiter overhead A/B (token bucket; limiter on vs off; medians, ms)",
    "### B0 baseline (100 RPS)",
    "### B4 headroom vs throttled (5,000 RPS)",
    "### B5 burst (10,000 requests in 1 s, one fresh user)",
    "### B6 concurrency",
    "### B7 distributed: Nginx vs direct (5,000 RPS)",
    "### B8 hot key",
    "### B9 Redis pooling",
    "### B10 correctness under load (throttled, 2,000 RPS)",
    "## Correctness (B5 burst bound, B6 exactly 100 of 1,000, B10 per-user bounds)",
    "## Redis memory per rate-limit key (MEMORY USAGE samples, valid B1 runs)",
    "## Targets (requirements) vs measured",
]

TAIL = """
## Invalid runs

All invalid runs, each with its reasons, are listed in the last section of
`benchmarks/reports/phase-3b-final/report.md`; their artifacts stay in `benchmarks/results/`.

## Reproduce

See `14-deployment-guide.md` §5d (running the benchmarks) and `benchmarks/reports/phase-3b-final/analysis.md` §10.
"""

body = [HEAD]
for h in PARTS:
    block = section(h)
    body.append(demote(block, 1) if h.startswith("## ") else block)
body.append(TAIL)
(ROOT / "docs" / "11-performance-benchmarks.md").write_text("\n\n".join(body).rstrip() + "\n")
print("docs/11 rebuilt from report.md:", len(PARTS), "sections")
