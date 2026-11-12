# 02 — Requirements

> Status: **baseline agreed in Phase 0; verification recorded at the end of this page** (Phases 1–3 complete).

## Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | Limit requests per client within a configurable interval; reject excess requests with HTTP 429. |
| FR-2 | Enforce one shared quota across all application instances. |
| FR-3 | Support Token Bucket, Fixed Window Counter and Sliding Window Counter, selectable per policy. |
| FR-4 | Identify clients by authenticated user id, or by client IP when anonymous. |
| FR-5 | Select policies by endpoint pattern, HTTP method and subscription tier (ANONYMOUS, FREE, PREMIUM, ENTERPRISE). |
| FR-6 | CRUD API for policies plus per-policy statistics (`/api/v1/policies`, `/api/v1/policies/{id}/stats`). |
| FR-7 | Policy changes take effect on every instance without restart. |
| FR-8 | Return `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`; on 429/503 also `Retry-After` and a JSON body. |
| FR-9 | Configurable fail-open / fail-closed behaviour when Redis is unavailable, globally and per policy. Fail-closed returns 503, not 429. |

## Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | Redis is the single authoritative source of rate-limit state; instances keep no authoritative counters. |
| NFR-2 | Every check that reads and writes state runs as one atomic Lua script. No GET-then-SET from Java. |
| NFR-3 | Time used by algorithms comes from Redis `TIME` inside the script (one clock for all instances). A `now` override exists only in internal test interfaces. |
| NFR-4 | MySQL is never queried on the per-request path. |
| NFR-5 | No shared hot keys for analytics: statistics are per-instance shards, aggregated on read. |
| NFR-6 | Prometheus labels are bounded (algorithm, result, instance); never user id, IP or raw path. |
| NFR-7 | Secrets come from environment variables; none are committed. |

## Performance requirements (targets, not results)

| Metric | Target |
|---|---|
| Throughput | 5,000 RPS across 3 instances |
| Rate-limiter overhead (in-process, excluding HTTP) | avg < 5 ms, P95 < 10 ms, P99 < 20 ms |
| Functional enforcement accuracy | 100 % (exact accepted count in concurrency tests) |
| Concurrency test | ≥ 1,000 simultaneous requests |
| Non-429 error rate under supported load | < 0.1 % |

Functional correctness (accepted / rejected counts) and performance (latency, throughput) are measured
and reported **separately**. HTTP 429 is an expected outcome and is never counted as an error.

## Reliability requirements

- Defined behaviour for Redis down/slow/exhausted, MySQL down, invalid policies, missing identity, instance loss (see `13-failure-handling.md`).
- Readiness reflects whether the application is initialised and a usable policy snapshot is loaded. Redis and MySQL availability do not gate traffic (decision R1, 2D); their health is visible separately in `/actuator/health/dependencies`. *(Superseded the Phase 1 rule "readiness reflects Redis, MySQL and the policy snapshot".)*
- Redis outages are contained by a circuit breaker: once open, requests receive their failure mode without contacting Redis.
- In production, an instance that cannot load policies from MySQL reports **not ready**; it does not serve configuration fallbacks. Fallbacks are a development-only convenience.
- No availability percentage is claimed from local Docker tests.

## Security requirements

- JWTs validated for signature and expiry; signing secret from the environment.
- The demo token endpoint exists only in the development profile.
- `X-Forwarded-For` is honoured only from configured trusted proxies.
- Policy management endpoints require an admin role.
- Error responses do not expose internal policy ids or Redis details.

## Verification (status after Phase 3B)

| Requirement | Verified by | Status |
|---|---|---|
| FR-1, FR-8 | `RateLimitFilterIT`, 2C/3A smoke tests (headers, 429 body, `Retry-After`) | ✅ |
| FR-2 | `DistributedHttpRateLimitIT` (3 instances, exact quota); B6 (exactly 100 of 1,000 across 3 instances, 6 runs) | ✅ |
| FR-3 | Script ITs, differential test, B1/B4/B5/B8/B10 for all three algorithms | ✅ |
| FR-4, FR-5 | `IdentityAndResolutionIT`, `TrustedProxyIT`, policy resolution unit tests | ✅ |
| FR-6 | `PolicyApiIT`, `StatsDistributedIT`; `/stats` reconciled exactly in every valid benchmark run | ✅ |
| FR-7 | `PolicyPropagationIT`; benchmark policy switching verified on all three instances before every run | ✅ |
| FR-9 | `FailureModeIT`, `CircuitBreakerIT`; F1, F2 and F5 under load (fail-open 200s, fail-closed 503s, no 429s) | ✅ |
| NFR-1 – NFR-6 | Design and tests above; bounded labels checked in `ObservabilityIT` | ✅ |
| NFR-7 | 3A secret scan (no secrets in files or image); Phase 4 security review (`16-security-review.md`) | ✅ |
| Throughput 5,000 RPS (3 instances) | B1: valid for all three algorithms, within every criterion (`11-performance-benchmarks.md`) | ✅ met (laptop) |
| Limiter overhead avg < 5 / P95 < 10 / P99 < 20 ms | B1 in-process histogram: P99 ≤ 1.7 ms up to 7,500 RPS | ✅ met |
| Enforcement accuracy 100 % | B5, B6, B10: zero over-admission in every run | ✅ met |
| Concurrency ≥ 1,000 | B6 and smoke AC14: 1,000 simultaneous requests | ✅ met (warm instances; cold-start limitation in `13-failure-handling.md`) |
| Non-429 error rate < 0.1 % | 0 non-429 errors in every valid B1 run | ✅ met |
