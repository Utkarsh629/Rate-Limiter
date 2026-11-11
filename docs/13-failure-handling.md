# 13 — Failure Handling

> Status: **Redis/MySQL failure handling, circuit breaker and readiness semantics implemented and verified (Phase 2, 2C + 2D).** Instance failure behind Nginx is verified in Phase 3.

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
| Instance dies | Nginx skips it after a failed request (**passive** health checks only: `max_fails=1 fail_timeout=5s`, `proxy_next_upstream error timeout` retries idempotent requests on another instance); quota state survives in Redis; up to 5 s of unflushed `/stats` counts lost. Client-visible errors are measured in Phase 3B (F3) |
| CPU-constrained burst (1,000 simultaneous requests, 1.5-CPU instances) | With a 50 ms Redis command timeout: client-side Lettuce timeouts → fail-open → breakers opened, although Redis p99 was ~61 µs. **Deployment uses 250 ms** (D42): 4/4 bursts exact, 0 fail-open, 0 breaker openings |
| `/stats` flush while Redis is down or paused | ✅ Batch kept and retried unchanged; applied exactly once even if the timed-out attempt runs later (3A tests) |
| Redis restart (no persistence) | All quotas reset |
| Redis failover (async replication) | Brief over-admission possible |
| Hot key | Documented; advanced mitigation is a local negative cache |

## Sections to complete
Fail-open vs fail-closed trade-offs per endpoint class, Redis Cluster key distribution and limits, test evidence for each row.
