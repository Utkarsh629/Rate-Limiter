# 13 — Failure Handling

> Status: **Redis/MySQL failure handling, circuit breaker and readiness semantics implemented and verified (Phase 2, 2C + 2D). Failure and recovery under load measured in Phase 3B (F1–F5, below).** One issue is open: cold-start fail-open under bursts (below).

## Verified in Phase 1
| Scenario | Observed |
|---|---|
| Redis stopped while app running | *Superseded by R1 (2D):* in Phase 1 readiness went 503. Now readiness stays 200 and `/actuator/health/dependencies` reports `redis` DOWN (verified: `CircuitBreakerIT`, manual Compose) |
| SIGTERM | graceful shutdown, in-flight requests drained (20 s limit) |

## Decided behaviour (to implement and test)
| Scenario | Behaviour |
|---|---|
| Redis down / timeout (50 ms) | ✅ FAIL_OPEN → 200 without rate-limit headers; FAIL_CLOSED (policy or global) → **503** + `Retry-After: 5`, never 429. Until the breaker opens, ~55–59 ms per request (9–10 requests); then **circuit breaker OPEN**: Redis not called, decision p99 19 µs in-process, HTTP p99 1.6 ms; recovery OPEN → CLOSED in 5.2–5.3 s via 3 successful probes (verified in `CircuitBreakerIT`, `FailureModeIT`, manual Compose) |
| Redis connection pool exhausted (pool mode) | ✅ Classified as an infrastructure failure, failure mode applied, breaker opens (verified with pool size 1). Note: pool mode now really applies to script calls (`RedisPoolingConfiguration`) |
| Redis replies "cannot serve" (OOM, BUSY, LOADING, READONLY, …) | Counted as infrastructure failures (unit-tested classification) |
| Lua runtime / programming errors | Never trip the breaker (unit-tested); store errors still map to the failure mode as in 2C |
| Redis restart (script cache lost) | ✅ `NOSCRIPT` answered by an `EVAL` fallback inside the same call; breaker unaffected; quotas restart from full (verified with `SCRIPT FLUSH` + `FLUSHALL`) |
| Commands sent during an outage | Executed late when Redis resumes (timeouts don't cancel); can only consume quota, never over-admit. Documented in docs/09 |
| Fresh instance, first requests | Scripts preloaded (`SCRIPT LOAD`) before readiness, so the first request doesn't exceed the timeout. After a Redis **restart** the script cache is empty: the first call per instance needs `NOSCRIPT → EVAL` (two round trips) |
| MySQL down at runtime | ✅ Verified (unit + `CircuitBreakerIT` + manual Compose): last good snapshot keeps limiting; readiness stays 200 (R1); `dependencies` shows `db` DOWN. Policy CRUD returns 503 |
| MySQL down at startup, `dev` | ✅ Verified (`StartupWithoutMySqlIT`): configuration fallback policies served, logged as WARN; readiness 200 under R1 (was 503 before 2D) |
| MySQL down at startup, `prod` | ✅ Verified: **no fallback**; readiness DOWN; deferred migration + load retried every reload interval; READY by itself once MySQL appears |
| Pub/Sub message lost | ✅ Verified: periodic reload picks up the change (`PolicyPropagationIT`) |
| Invalid JWT (signature, expiry, issuer) | ✅ Verified: 401, never downgraded to anonymous |
| Invalid policy | Rejected by API validation (400) and by DB CHECK constraints (MySQL error 3819) |
| No authenticated user | Tier ANONYMOUS, keyed by client IP |
| Instance dies | Nginx skips it after a failed request (**passive** health checks only: `max_fails=1 fail_timeout=5s`, `proxy_next_upstream error timeout` retries idempotent requests on another instance); quota state survives in Redis; up to 5 s of unflushed `/stats` counts lost. Measured in Phase 3B (F3): no client-visible errors while app2 was down |
| CPU-constrained burst (1,000 simultaneous requests, 1.5-CPU instances) | With a 50 ms Redis command timeout: client-side Lettuce timeouts → fail-open → breakers opened, although Redis p99 was ~61 µs. **Deployment uses 250 ms** (D42): 4/4 bursts exact in the 3A smoke test; warm instances exact in Phase 3B (B6 6/6). **Open issue:** shortly after an instance restart a burst can still exceed 250 ms and fail open (see *Cold-start fail-open* below) |
| `/stats` flush while Redis is down or paused | ✅ Batch kept and retried unchanged; applied exactly once even if the timed-out attempt runs later (3A tests) |
| Redis restart (no persistence) | All quotas reset |
| Redis failover (async replication) | Brief over-admission possible |
| Hot key | Documented; advanced mitigation is a local negative cache |

## Failure and recovery under load (Phase 3B, F1–F5)

Constant 2,000 RPS through Nginx: 90 % to the fail-open benchmark endpoint (`/api/v1/demo/profile`, bench-profile,
fixed window) and 10 % to the fail-closed endpoint (`/api/v1/demo/enterprise`, sliding window, `FAIL_CLOSED`).
Faults were injected by `scripts/bench/run.sh` 30 s into the measurement window; per-second status timelines come from
the Nginx access log, breaker states from Prometheus (5 s scrapes, queried at 1 s), the raw logs are saved with each
run. Full timelines: `benchmarks/reports/phase-3b-final/report.md` (F1–F5 sections); summary and analysis:
`analysis.md` §6. Runs (first of each): `benchmarks/results/*-f1-…-r1` … `*-f5-…-r1`.

| Fault | Fail-open endpoint | Fail-closed endpoint | Breakers (each instance independently) | Recovery |
|---|---|---|---|---|
| F1 `docker compose pause redis`, 20 s | 37,757 × 200 without rate-limit headers; **0 × 429** | 4,030 × 503; **0 × 429** | OPEN at +1 / +4 / +5 s, CLOSED at +24–26 s | 5 s open wait + 3 successful probes after unpause |
| F2 `docker compose restart redis` | brief fail-open (9,426 decisions) | 251 × 503 during the restart | OPEN ~4 s each | CLOSED by +9–11 s; quotas reset (no persistence) |
| F3 `docker compose kill app2`, start after 30 s | **no client-visible errors**: Nginx passive failover to app1/app3 | no errors while app2 was down | app2 OPEN briefly after its restart | 122 × 503 on the fail-closed endpoint right after app2 rejoined (cold start, below) |
| F4 `docker compose stop mysql`, 60 s | **no effect** on rate limiting (cached policy snapshot) | no effect | never opened | policy API 503 during the outage; readiness 200 on all instances |
| F5 Redis slowed (Lua CPU loop via `EVAL`: 142 ms blocks every 0.5 s for 30 s, then 388 ms every 1 s for 30 s) | 142 ms blocks absorbed (below the 250 ms timeout) | 5,263 × 503 in the 388 ms phase | OPEN only in the 388 ms phase | CLOSED 1–6 s after the blocks ended |

Infrastructure failures were never reported as 429: all 429s in these runs are Redis-decided rejections. F5 does not
use Redis `DEBUG` (disabled in this deployment, `enable-debug-command no`): a pure-CPU Lua loop sent with the standard
`EVAL` blocks Redis's command thread for a calibrated time (Redis measured each block). All F runs are INVALID under
the generic benchmark rules, for reasons inherent to the fault: dropped load-generator iterations while requests
wait on the timeout; for F2/F3/F4 also count reconciliation (Redis data lost, restarted-instance counters reset, an
admin API check during F4). The timelines are the deliverable.

## Cold-start fail-open under bursts (open issue)

**Symptom.** Shortly after the instances (re)start, a 1,000-request simultaneous burst can produce fail-open
responses: smoke test AC14 failed in 2 of 4 rounds on a freshly built stack (10 and 13 fail-open) and in 3 of 4 rounds
about 3 minutes after the instances were recreated (11 / 333 / 15 fail-open); app2 rejoining in F3 failed closed for
122 requests. Warm instances are exact: B6 6 of 6 runs, smoke 37/37 on the warm stack.

**Cause (measured, `benchmarks/investigations/cold-start/`).** CPU-quota starvation of the Redis client's I/O thread:

- Each instance is limited to 1.5 CPUs (cgroup quota, 100 ms periods). A 1,000-request burst (~333 per instance, all
  within about a second) costs each instance 0.6–1.6 s of CPU, of which **0.5–1.5 s is JIT compilation**. A
  1,000 RPS warm-up hardly helps (292 ms of JIT in 60 s, no throttling), because 90 % of a burst takes the 429
  rejection path and Tomcat grows from 10 to 200 threads during the first bursts.
- The kernel then throttles each instance for 0.4–3.2 s in total per burst (2–8 throttled periods). While throttled,
  the Lettuce I/O thread cannot read Redis replies, so the client-side duration of a script call reached
  **181–213 ms**, against the 250 ms timeout. Redis itself answers in microseconds (server P99 < 50 µs).
- JIT time per burst falls with repetition (about 1.3 s → 0.6 s per instance over four bursts) but does not vanish;
  even warm, the margin to the timeout stays small (~40–60 ms). Calls that cross 250 ms time out → fail-open (or 503
  for fail-closed policies) → if enough fail, the breaker opens.

**Evaluated fix: warm-up before readiness.** Readiness currently waits for policies and script preloading only. A
warm-up would help only if it reproduced burst conditions (high concurrency and the rejection path); steady traffic
measurably does not. It would add new start-up behaviour to the application, so it was **not implemented** in Phase
3B. The Redis timeout (250 ms, D42) and the failure semantics are **unchanged**.

**Status: unresolved.** Options for review, each to be measured before adoption: (1) a start-up warm-up that drives
the allowed and rejected paths concurrently before readiness; (2) pre-starting Tomcat threads; (3) more CPU per
instance, or limiting JIT threads; (4) revisiting the Compose timeout with this evidence.

## Remaining documentation work (Phase 4)
Fail-open vs fail-closed trade-offs per endpoint class; Redis Cluster key distribution and limits.
