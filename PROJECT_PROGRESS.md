# Project Progress

A feature is checked only when it is **implemented and verified** (test or measured run), not when code exists.

Last updated: 2026-11-12 — Phases 1, 2, 3A and 3B approved. Phase 3B committed (d31f6cd, branch `phase-3b/benchmark-infrastructure`, not pushed). **Phase 4 (Documentation, Audit & Release) in progress** on branch `phase-4/documentation-release` (uncommitted): documentation, security review, README, interview preparation done; release audit passed; release steps pending approval.

> **Phase 3B evidence:** `benchmarks/reports/phase-3b-final/` (`report.md` generated from `benchmarks/results/`, `analysis.md` with the acceptance checklist), `benchmarks/investigations/cold-start/`. Earlier handoff: [PROJECT_HANDOFF.md](PROJECT_HANDOFF.md). Latest verification (2026-11-12, after the approved Phase 4 security fixes SEC-1/3/6 and removal of the nested folder): `./mvnw clean verify` 318/318 (unit 165, integration 153); smoke 37/37 on a freshly rebuilt stack (report `benchmarks/smoke/20261112-102259/`; AC14 can still fail shortly after a restart under the open cold-start issue); benchmark evidence checksums 6,257/6,257 OK; no secrets in changed files. Before the fixes: 310/310 and 37/37 (`benchmarks/smoke/20261112-101056/`).

## Roadmap (four consolidated phases)

| Phase | Name | Status |
|---|---|---|
| 1 | Foundation & Architecture | ✅ Completed and approved |
| 2 | Core Backend & Distributed Logic | ✅ **Completed and approved** (2A, 2B, 2C, 2D) |
| 3 | Observability, Deployment & Performance (two parts: 3A, 3B) | ✅ **Completed and approved** (3A, 3B) |
| 4 | Documentation, Audit & Release | 🔄 In progress (release steps await approval) |

### Phase 1 — Foundation & Architecture ✅
Architecture proposal, requirements, Maven/Spring Boot skeleton, Redis + MySQL Compose services, Flyway, configuration and profiles, documentation structure, git initialisation. Checklist and evidence below.

### Phase 2 — Core Backend & Distributed Logic ✅ Completed and approved

| Scope item | Workstream | Status |
|---|---|---|
| Rate-limiting algorithms and Redis Lua scripts | 2A | ✅ Approved |
| Policy management, persistence and caching | 2B | ✅ Approved |
| JWT authentication and client identity resolution | 2B | ✅ Approved |
| Spring Security filter integration | 2C | ✅ Approved |
| Distributed correctness across multiple instances | 2A (algorithm level), 2C (HTTP, 3 instances) | ✅ Approved |
| Fail-open / fail-closed behaviour | 2C | ✅ Approved |
| Circuit breaker implementation | 2D | ✅ Approved |
| Remaining failure handling: Redis connection-pool exhaustion; readiness per R1 (Phase 1 docs/tests updated) | 2D | ✅ Approved |
| Concurrency, integration and failure-mode testing | 2A–2D | ✅ Approved (288 tests passing at close) |

**Phase 2 exit criteria: met.** All workstreams implemented and verified, `./mvnw clean verify` green (288 tests), docs updated, approved 2026-11-10.

### Phase 3 — Observability, Deployment & Performance 📝 (plan: `docs/proposals/phase-3-plan.md`)

Exactly two parts. **3A must be fully implemented, tested and approved before 3B begins.**

| Part | Name | Status |
|---|---|---|
| **3A** | **Observability & Deployment** | ✅ Approved |
| **3B** | **Performance & Optimization** | ✅ **Approved** (212 runs: 153 valid, 59 invalid kept with reasons) |

**3A — Observability & Deployment** (acceptance 3A-AC1…AC20 in the plan)

| Scope item | Status |
|---|---|
| Micrometer metrics: the 7 required `ratelimiter_*` meters, bounded labels, sub-millisecond histograms | ✅ |
| Prometheus integration (`/actuator/prometheus`, scrape config, Redis exporter) | ✅ |
| `GET /api/v1/policies/{id}/stats` with per-instance shards (5 s flush; unflushed counts may be lost on crash), exactly-once batches | ✅ |
| Dockerfile; complete Docker Compose deployment (env, secrets from `.env`, volumes, resource limits) | ✅ |
| Three Spring Boot instances behind Nginx (`TRUSTED_PROXIES` = Nginx address; `/actuator/**` blocked) | ✅ |
| Health checks on readiness (R1); dependency health visible | ✅ |
| Grafana dashboards (8 required panels + breaker/Redis panels), provisioned | ✅ |
| Automated smoke test (`stack-smoke.sh`): stack health, distribution, monitoring populated, 1,000-concurrency exact quota (×4), 60 s functional load with counts reconciled | ✅ |
| 3A documentation (docs 03, 04, 06, 07, 10, 12, 13, 14; plan §9) | ✅ |

**3B — Performance & Optimization** (acceptance 3B-AC1…AC16 in the plan)

| Scope item | Status |
|---|---|
| k6 tooling: run script, collectors, `docker stats` sampler, reconciliation, validity checks, report (tables; charts deferred, O1) | ✅ `scripts/bench/`, `load-tests/k6/`; 49 Python tests + 75-case selftest |
| Benchmark scenarios B0–B10 | ✅ 3 valid runs per configuration; **7,500 RPS** highest validated B1 level and maximum sustainable throughput (laptop); 10,000 RPS attempted, all invalid (target not achieved); B2 knee 11,000 RPS (load-generator bound) |
| Token Bucket / Fixed Window / Sliding Window comparison | ✅ docs/11 |
| Redis pooling enabled vs disabled | ✅ pool-16 no better (slightly worse P99); shared connection kept |
| P50/P95/P99 (limiter overhead, Redis round trip, E2E kept separate), throughput, error rate, CPU, memory, Redis latency/resources | ✅ docs/11 (limiter P99 ≤ 1.7 ms up to 7,500 RPS) |
| Failure and recovery under load F1–F5 (Redis pause, Redis restart, app kill, MySQL stop, Redis slowed) | ✅ docs/13 (timelines; fail-open/closed as designed, no 429 for infrastructure failures) |
| Bottleneck analysis and evidence-based optimisation | ✅ no optimisation justified; every candidate rejected with evidence (`benchmarks/reports/phase-3b-final/analysis.md`) |
| 3B documentation (docs 10, 11, 12, 13, 14; per-key memory in docs/11) | ✅ approved |

Targets stay targets until a valid run shows them: 5,000 RPS throughput, limiter overhead < 5 / 10 / 20 ms (avg / P95 / P99), zero over-admission, < 0.1 % non-429 errors, load levels up to **10,000 RPS (target, not achieved)**.

### Phase 4 — Documentation, Audit & Release 🔄

| Scope item | Status |
|---|---|
| Docs 00–15 complete and consistent with the code; architecture diagrams | ✅ consistency pass done (statuses, D43–D50, results) |
| API documentation (incl. OpenAPI) | ✅ docs/07 + exported `docs/api/openapi.json` |
| Benchmark report (measured results only, environment and same-machine limitations) | ✅ docs/11, `benchmarks/README.md`, charts in `docs/assets/` (generated from saved data) |
| Deployment instructions | ✅ docs/14 |
| Failure-handling documentation | ✅ docs/13 (F1–F5, cold-start limitation) |
| Security review | ✅ docs/16 (12 findings, none High); SEC-1 (local part), SEC-3, SEC-6 remediated and nested folder removed (approved 2026-11-12); others documented as deployment recommendations |
| Interview preparation (2- and 5-minute explanations, 30 questions, trade-offs) | ✅ docs/15 |
| Resume descriptions (ATS, technical, short; measured metrics only) | ✅ docs/15 |
| README (recruiter-facing, with real results and charts) | ✅ |
| Final release audit against the acceptance criteria | ✅ all criteria met; release steps (commit, merge, tag, push) await approval |

## Phase 1 checklist

- [x] Maven project (`com.utkarsh:distributed-rate-limiter`), Spring Boot 3.5.16, Java 17, Maven wrapper 3.9.11
- [x] Package structure `com.utkarsh.ratelimiter.{algorithm,redis,filter,identity,policy,resilience,metrics,security,demo,config}`
- [x] Typed, validated `ratelimiter.*` properties (instance id, key prefix, default failure mode, policy reload, fallback flag, stats flush 5 s)
- [x] Profiles: base (secure defaults), `dev`, `prod`, `test`
- [x] Redis service in Compose (`redis:7.4-alpine`, persistence off, `volatile-ttl`, loopback-only port, healthcheck)
- [x] MySQL service in Compose (`mysql:8.4`, volume, UTC, healthcheck, host port 3307)
- [x] Flyway V1 schema with CHECK constraints, V2 seed policies
- [x] `.env.example`; Compose refuses to start without required secrets; `.env` git-ignored
- [x] Readiness group (`readinessState`, `db`, `redis`) and liveness group
- [x] Graceful shutdown
- [x] Documentation structure `docs/00–15`; 01, 02, 04, 08, 10, 14 written for current state
- [x] Git repository initialised (no commits yet)

### Verification evidence (2026-11-10)

- [x] `./mvnw verify` → unit 6/6, integration 9/9 (Testcontainers Redis 7.4 + MySQL 8.4), BUILD SUCCESS
- [x] Redis connectivity (PING) and Lua `TIME` execution
- [x] MySQL connectivity (8.4.x)
- [x] Flyway: V1 + V2 applied, none pending (test, `/actuator/flyway`, `flyway_schema_history`)
- [x] CHECK constraints enforced (MySQL error 3819) and unique name enforced
- [x] Compose: both services healthy; Redis `CONFIG GET` confirms config
- [x] App against Compose: started in 2.15 s, readiness 200; Redis stopped → readiness 503 / liveness 200; recovered → 200 *(readiness behaviour superseded by R1 in 2D: Redis outages no longer make the instance unready)*

## Phase 2A checklist — algorithm foundation (approved)

- [x] `RateLimitAlgorithm` strategy interface; `AlgorithmRegistry` (fails fast on missing/duplicate)
- [x] Domain: `RateLimitKey`, `RateLimitRule` (bounded for exact Lua arithmetic), `RateLimitResult` (validated invariants), `AlgorithmType`, `SubjectType`
- [x] Redis abstractions: `RateLimitScriptExecutor` / `RedisTemplateScriptExecutor` (EVALSHA + NOSCRIPT fallback, errors → `RateLimitStoreException`), `RateLimitKeyFactory`, `RateLimitScript`
- [x] Clock: `RedisServerClock` hard-wired in production; `ManualScriptClock` in test sources only
- [x] Token bucket Lua (integer units, refill, exact retry/reset, TTL)
- [x] Fixed window Lua (single hash key with window index, HINCRBY, TTL)
- [x] Sliding window counter Lua (exact integer admission test, closed-form retry, TTL)
- [x] Atomic initialization of missing keys; rejections write nothing; clock moving back never grants quota
- [x] TTL never fires early (+1 s grace) — bug found and fixed by tests
- [x] Spring wiring (`RateLimitAlgorithmConfiguration`)
- [x] Docs 05, 06, 09 rewritten; 04 (D16–D20) and 10 updated

### Verification evidence (2026-11-10)

- [x] `./mvnw clean verify` → unit 31/31, integration 84/84 (115 total), BUILD SUCCESS
- [x] Each algorithm: normal, boundary, expiration (logical + real Redis TTL), deterministic timing, retry exact to 1 ms
- [x] Concurrency: 1,000 simultaneous requests, limit 100 → exactly 100 admitted, 5 rounds × 3 algorithms, plus Redis-TIME variant
- [x] Three independent Redis clients share one quota → aggregate exactly 100 for each algorithm
- [x] Negative control: unsafe GET-then-SET admitted 1,000/1,000 with the same harness
- [x] Differential test: Lua = independent Java model over 27,000 random steps; two planted bugs caught

## Phase 2B checklist — policy management & identity (approved)

- [x] `RateLimitPolicy` JPA entity + `PolicyRepository` (mapping validated against MySQL in every IT)
- [x] REST API: POST / GET (paged) / GET by id / PUT (optimistic locking) / DELETE under `/api/v1/policies`, ROLE_ADMIN
- [x] Bean Validation mirroring DB constraints; PathPattern parse check; RFC 7807 problem responses with field errors
- [x] MySQL 3819 (CHECK) → 400 naming the field; 1062 (duplicate) → 409; stale version → 409
- [x] `PolicySnapshot` (pre-compiled, pre-sorted, lock-free) + `PolicyResolver` (most-specific match, USER/IP quota key)
- [x] `PolicyCache`: startup load, reload coalescing, last-good on failure
- [x] Invalidation via Redis Pub/Sub after commit; periodic reload every 30 s
- [x] JWT (HS256): signature, exp/nbf (30 s skew), issuer; `JWT_SECRET` from env, ≥ 32 bytes enforced
- [x] Dev-only demo token endpoint + whoami (property AND `@Profile("!prod")`)
- [x] Client identity: JWT `sub` + `tier` (never escalates); IP from Tomcat RemoteIpValve with explicit trusted proxies (default none)
- [x] Tier-based policies resolved end to end and handed to `AlgorithmRegistry` (no algorithm logic duplicated)
- [x] Readiness contributor `policies`
- [x] Dev-only fallback policies; production stays NOT READY without MySQL
- [x] Startup with MySQL down (deferred Flyway, no Hibernate DB access at boot) and automatic recovery
- [x] OpenAPI / Swagger UI in dev profile only
- [x] Docs 07, 08, 10 updated; 04 (D21–D27), 13, 14, `.env.example` updated

### Verification evidence (2026-11-10)

- [x] `./mvnw clean verify` → unit 71/71, integration 117/117 (188 total), BUILD SUCCESS, no Hibernate deprecation warnings
- [x] Policy changes across **3 application contexts**: create/update/delete via different instances visible everywhere via pub/sub; direct DB change picked up by periodic reload
- [x] Startup without MySQL: NOT READY → MySQL started → migrated + READY with no restart; dev fallback served
- [x] JWT: expired / wrong key / wrong issuer / garbage → 401; non-admin → 403; invalid token not downgraded to anonymous
- [x] Spoofed X-Forwarded-For ignored by default; right-most untrusted hop used when the proxy is trusted
- [x] Dev endpoints absent when disabled and under `prod` even if the property is forced on
- [x] Manual run (dev profile, Docker Compose): readiness, demo token, CRUD, errors, whoami, OpenAPI

## Phase 2C checklist — HTTP rate-limiting filter (approved)

- [x] `RateLimitFilter` (OncePerRequestFilter) reusing `ClientIdentityResolver`, `PolicyResolver`, `AlgorithmRegistry`, `RateLimitResult`; no algorithm logic duplicated
- [x] Placed in the Spring Security chain after `BearerTokenAuthenticationFilter`, before authorization; automatic servlet registration disabled (no double counting)
- [x] Excluded paths (actuator, API docs, Swagger, `/api/v1/auth/**`, `/error`), configurable
- [x] `X-RateLimit-Limit/Remaining/Reset` on every covered response; `Retry-After` on 429/503, rounded up
- [x] 429 body: RFC 7807 + `error`/`message`/`retryAfterSeconds`; controller not invoked
- [x] Redis failures: FAIL_OPEN (continue, no headers) / FAIL_CLOSED (503, never 429); policy override > global default; log flood protection
- [x] Demo endpoints `/public`, `/profile`, `/premium`, `/enterprise`; V3 endpoint-specific FAIL_CLOSED demo policy
- [x] Script preloading before readiness (cold-start fail-open found and fixed)
- [x] One policy per request preserved (no stacking)
- [x] Docs 07, 09, 10, 14 updated; also 03, 04 (D28–D32), 08 (V3), 13

### Verification evidence (2026-11-10)

- [x] `./mvnw clean verify` → unit 94/94, integration 136/136 (230 total), BUILD SUCCESS
- [x] **Distributed HTTP correctness verified:** 3 application instances, 300 concurrent requests from one identity → exactly 50 accepted for token bucket, fixed window and sliding window; anonymous per-IP → exactly 10 of 300
- [x] Tier quotas over HTTP: FREE 20, PREMIUM 100, ENTERPRISE 1000 (exact); anonymous 10 per IP; trusted-proxy rules still enforced
- [x] Failure modes over HTTP with Redis paused: fail-open 200, fail-closed 503 (policy and global), ~55–58 ms per request, recovery without restart; passed 3 consecutive runs
- [x] Filter ordering: JWT identity used; 401/403 counted; invalid tokens rejected before limiter; one count per request
- [x] Manual run against Docker Compose (dev): 200 with headers, 429, 503, fail-open, recovery

## Phase 2D checklist — Redis circuit breaker (approved)

- [x] Resilience4j 2.4.0 (`resilience4j-circuitbreaker`, `resilience4j-micrometer`); Micrometer 1.15.12 (Boot BOM) compatibility verified by test
- [x] `CircuitBreakingScriptExecutor` at the script-executor boundary; algorithms, Lua, policies, JWT, filter unchanged
- [x] CLOSED/OPEN/HALF_OPEN with approved values (20 / 10 / 50 % / 5 s / 3 probes / 2 s)
- [x] 3 of 3 probes required; failed probe forces OPEN before it is reported; concurrent probe races tested
- [x] Probe timeout per in-flight probe (R5 option B); stale results (> 2 s) never recorded
- [x] `RedisFailureClassifier`: only infrastructure failures count
- [x] Refused calls → existing fail-open / fail-closed (503, Retry-After 5), never 429
- [x] Metrics: Resilience4j binding + `ratelimiter_circuit_breaker_transitions_total{from,to}` + `ratelimiter_redis_errors_total{type}`; `rateLimitStore` health
- [x] Readiness = readinessState + policies (R1); `dependencies` health group; Phase 1 tests/docs updated
- [x] Pool exhaustion handled (`PoolException` now caught; pool mode now applies to script calls)
- [x] NOSCRIPT after script-cache loss handled without tripping
- [x] Docs 02, 04 (D33–D37), 07, 09, 10, 13, 14, design doc §17 updated

### Verification evidence (2026-11-10)

- [x] `./mvnw clean verify` → unit 144/144, integration 144/144 (**288**), BUILD SUCCESS
- [x] No Redis calls while OPEN: Redis `INFO commandstats` EVALSHA 10 → 10 (test), 34 → 34 over 50 requests (manual Compose)
- [x] Decision while OPEN: in-process p99 19 µs; HTTP p99 1.6 ms (vs ~58 ms per request before opening)
- [x] Recovery OPEN → CLOSED: 5.18 s (test), 5.33 s (manual)
- [x] Exactly 3 of 50 concurrent half-open requests reached Redis
- [x] 3 instances recover independently; shared quota exact (50/300) after recovery
- [x] Fail-open / fail-closed correct in all 16 unit combinations and over HTTP
- [x] Manual Compose: outage → OPEN after 9 requests; readiness 200 / dependencies 503; fail-open 200, fail-closed 503 + Retry-After 5; recovery; MySQL stop → readiness 200, limiting continues

## Phase 3A checklist — Observability & Deployment (awaiting review)

- [x] 3A.1 `RateLimitTelemetry` + filter hook (observation only, old constructor kept: P1); 7 required meters, bounded labels, histograms from 50 µs; Redis round-trip timer in the executor; `/actuator/prometheus` (P2)
- [x] 3A.2 `/stats`: `LongAdder` counters, per-instance shards `rl:stats:{policyId}:instanceId`, exactly-once (boot, seq) batches via `stats_apply.lua` (EVAL), 5 s flush, 24 h TTL, endpoint with 401/403/404
- [x] 3A.3 Dockerfile (multi-stage, layered, non-root, JRE 17), Compose with 9 services, static IPs, readiness health checks, resource limits, volumes, `.env` secrets; Nginx (round robin, keepalive, `X-Upstream`, `/actuator` blocked); Prometheus scrape config; redis_exporter
- [x] 3A.4 Grafana: provisioned datasource + 14-panel dashboard (8 required)
- [x] 3A.5 `mint-tokens.py`, k6 smoke scripts, `stack-smoke.sh` / `stack_smoke.py`, `stack-up.sh`
- [x] **D42**: Redis command timeout 250 ms in the Compose deployment (`STACK_REDIS_COMMAND_TIMEOUT`), application default 50 ms unchanged
- [x] Fixes found during 3A: script loading (D39), stats flush via EVAL (D41)

### Verification evidence (2026-11-11)

- [x] `./mvnw clean verify` → unit 158/158, integration 152/152 (**310**: 288 earlier, unchanged + 22 new), BUILD SUCCESS
- [x] Clean-state smoke `20261111-091138`: **PASSED 37/37** in 142 s
- [x] All 9 services healthy; timeout 250ms on app1/app2/app3
- [x] AC14 ×4 rounds: accepted=100 rejected=900 unexpected=0; 0 fail-open; 0 breaker openings; breakers closed before and after every round
- [x] AC18: 60 s at 1,000 req/s, 60,002 requests, 0 dropped iterations; k6 = Nginx log = raw counters of 3 instances = Prometheus (after final scrape) = `/stats` (after final flush): **57,391 allowed / 2,611 rejected / 0 fail-open**
- [x] AC10 distribution 11/10/9; AC11 caller 172.28.0.3 keyed correctly, spoof ignored; AC12 actuator 404 at Nginx, 4 targets up; AC13 no secret leaks; AC15–AC17 dashboard provisioned, 8 required panels with data, percentiles aggregate buckets
- [x] Earlier failing runs with 50 ms kept as evidence (docs/10): 371/573/865 accepted; diagnostic bursts 50 ms (425–909 fail-open) vs 250 ms and 500 ms (100/900 ×4, 0 fail-open)

## Known limitations (3A)

- 250 ms Redis command timeout is validated **for this deployment** (laptop, 1.5-CPU instances), not established as a universal production optimum; during an outage, the ~10 requests per instance before the breaker opens can wait up to 250 ms.
- Grafana screenshots are not saved automatically (no image renderer); panel data is verified through the API.
- Nginx (open source) has passive health checks only; failover errors are measured in 3B (F3).
- In this Docker Desktop environment, host-originated requests reach Nginx with a non-local source address; the client-IP check uses an in-network caller with a known address.
- The smoke test's latency and throughput are not results; Phase 3B measures them.

## Carried-forward items, by phase

**Phase 2**: none (closed).

**Phase 3A**: approved.

**Phase 3B**
- [x] k6 runs from the `grafana/k6` container (v2.3.0)
- [x] Benchmark Lettuce pooling on/off (B9)
- [x] Per-key `MEMORY USAGE` measurements (docs/11: 104–112 bytes per key; docs/06 not yet updated)
- [ ] Charts: deferred (decision O1; tables only)
- [x] k6 limits measured: CPU-bound from ~12,000 RPS (B2); memory fixed with `GOMEMLIMIT`
- [x] Benchmark tokens minted with a 48 h TTL
- [ ] **Open:** cold-start fail-open under 1,000-request bursts shortly after a restart (CPU-quota throttling during JIT; docs/13). Not fixed; options listed for review

**Repository (before 3B)** (handoff U1, U3, U4)
- [x] Move the project out of the session-scoped folder and make the first commit
- [x] Add `__pycache__/` to `.gitignore`
- [ ] Decide whether to keep the 3A smoke report (`benchmarks/smoke/20261111-091138/`, git-ignored) as committed evidence

**Phase 4**
- [x] Final docs pass, security review, interview prep, resume, README with measured results, release audit
- [x] Security remediation A–D (approved and applied 2026-11-12; docs/16)
- [ ] Approval: Phase 4 commit, merge to main, tag v1.0.0, push

## Final acceptance criteria (and the phase that closes each)

| Criterion | Phase | Status |
|---|---|---|
| All three algorithms work | 2 | ✅ algorithm level and HTTP |
| Redis maintains shared rate-limit state | 2 | ✅ |
| Lua scripts provide atomic execution | 2 | ✅ |
| Multiple Spring Boot instances work correctly | 2 / 3A | ✅ 3 instances over HTTP and behind Nginx (3A smoke) |
| Dynamic policies work | 2 | ✅ |
| HTTP 429 responses are correct | 2 | ✅ |
| Concurrency tests pass | 2 | ✅ |
| Distributed correctness is verified | 2 / 3A / 3B | ✅ HTTP, 3 instances; ✅ Compose + Nginx + k6 functional (3A); ✅ under benchmark load (3B: B6, B10, zero over-admission) |
| Redis failure handling is tested | 2 | ✅ fail-open/closed, circuit breaker, pool exhaustion, NOSCRIPT |
| Performance tests are executed | 3B | ✅ |
| Actual benchmark results are documented | 3B / 4 | ✅ docs/11 (generated); README |
| Prometheus metrics are available | 3A | ✅ |
| Grafana dashboard is functional | 3A | ✅ |
| Docker Compose starts the complete environment | 3A | ✅ |
| README is complete | 4 | ✅ |
| Technical documentation is complete | 4 | ✅ docs 00–16 |
| Project can be explained in an SDE interview | 4 | ✅ docs/15 |

## Known limitations (2B)

- Tier changes take effect only when a new token is issued; tokens can't be revoked before expiry (no denylist).
- IPv6 clients are limited per full address; an attacker with a /64 can rotate addresses (prefix-based keys are a possible improvement).
- Pub/Sub delivery is best-effort; worst-case staleness is the reload interval (30 s).
- A failed reload keeps the last good snapshot indefinitely; health reports `lastError` but stays UP (no staleness threshold yet).
- One policy per request (no stacking of per-IP and per-user limits).

## Known limitations (2C)

- During a Redis outage every covered request waits the 50 ms command timeout before its failure mode applies (circuit breaker pending).
- Timed-out Redis commands may still execute when Redis recovers, consuming quota (never over-admitting).
- Requests with invalid JWTs are rejected before the limiter and are not rate limited.
- After a Redis restart the script cache is empty; the first call per instance needs `NOSCRIPT → EVAL`.
- The admin API (`/api/**`) is limited by the caller's tier policy like any other endpoint.
- Only verified with direct instance ports; behind Nginx and under k6 load is pending. *(Resolved functionally in 3A: Nginx + k6 smoke; benchmark load is 3B.)*

## Known limitations (2D)

- Until the breaker opens, about 9–10 requests per instance still pay the 50 ms timeout at the start of an outage (by design: minimum 10 recorded calls).
- After Redis recovers, limits resume only after the 5 s open wait plus 3 successful probes. Requests in that gap get their failure mode.
- Breakers are per instance, so each instance pays its own ~10 timed-out requests before opening.
- Commands that timed out before the breaker opened still execute when Redis resumes (consume quota, never over-admit).
- With a healthy Redis at half-open, probes finish in ~1 ms and the breaker closes mid-burst; "exactly 3 probes" holds per half-open cycle.
- The per-instance breaker sees only one Redis endpoint; Sentinel / Cluster failover is out of scope (Phase 4 documentation).

## Known limitations (3A)

- The Compose deployment uses a 250 ms Redis command timeout (D42); at 50 ms the 1,000-concurrency burst failed open (425–909 requests per round) although Redis server p99 was ≈ 61 µs. 250 ms is validated for this laptop deployment only, not established as a production optimum. The application default stays 50 ms.
- During an outage, about 10 requests per instance pay the 250 ms timeout (Compose) before the breaker opens.
- Nginx (open source) has passive health checks only (`max_fails=1 fail_timeout=5s`).
- Host-originated requests reach Nginx with a non-local source address under Docker Desktop; the client-IP check uses an in-network caller.
- `/stats` loses at most one flush interval (5 s) of counts if an instance crashes.
- Grafana screenshots are not captured automatically (no image renderer).
- Smoke reports are git-ignored, so 3A evidence exists only locally until a decision is made (see Repository items above).
