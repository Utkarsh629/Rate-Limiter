# PROJECT HANDOFF — Distributed Rate Limiter

> Written 2026-11-11 at the end of the build session. Source of truth for progress: `PROJECT_PROGRESS.md` (kept consistent with this file).
> **Read §0 first.**

---

## 0. Critical facts before anything else

| # | Fact | Action |
|---|---|---|
| 1 | **Nothing is committed.** Branch `main` has no commits. The git index holds a stale Phase 1 snapshot; all later work is unstaged or untracked (§12) | Review, then make an initial commit (user approval required; never push without approval) |
| 2 | **The project lives in a folder the Claude desktop app created for this session.** Deleting that session deletes the folder and all uncommitted work | Move the session to a permanent folder, or copy the repository out and commit it, before deleting the session |
| 3 | **Phase 3A is implemented and verified but not yet approved.** Phase 3B must not start before approval | Get the 3A review |
| 4 | The full Docker Compose stack was running at handoff (9 services healthy) | `docker compose down` to stop (§5) |
| 5 | Docker Desktop quit once mid-session; restarting it and `docker compose up -d --build --wait` recovered everything | If commands fail with "Cannot connect to the Docker daemon", start Docker Desktop |

---

## 1. Architecture and technology stack

### 1.1 What the system does
A rate limiter for horizontally scaled Spring Boot services. Every instance shares **one quota per client in Redis**, updated **atomically with Lua scripts** (Token Bucket, Fixed Window Counter, Sliding Window Counter). Policies (per endpoint, method, subscription tier, keyed by user or IP) are managed at runtime through a REST API, stored in MySQL, cached in memory on every instance, and invalidated across instances via Redis Pub/Sub plus a 30 s periodic reload. JWT identity, an HTTP filter in the Spring Security chain, `X-RateLimit-*` headers, 429/503 responses, fail-open/fail-closed, a Redis circuit breaker, Prometheus metrics, Grafana, per-policy `/stats`, and a three-instance Docker Compose deployment behind Nginx.

### 1.2 Request path
```
Client → Nginx (172.28.0.10, round robin) → app1/app2/app3
  Tomcat RemoteIpValve (trusts only Nginx for X-Forwarded-For)
  → Spring Security: BearerTokenAuthenticationFilter (HS256 JWT: signature, exp/nbf, iss)
  → RateLimitFilter (excluded paths skipped)
       ClientIdentityResolver → PolicyResolver (in-memory snapshot, most-specific match)
       → AlgorithmRegistry → <Algorithm> → CircuitBreakingScriptExecutor → RedisTemplateScriptExecutor → Redis EVALSHA
       → allowed: X-RateLimit-* headers, continue | rejected: 429 | Redis failure: FAIL_OPEN continue / FAIL_CLOSED 503
       → RateLimitTelemetry.record(): Micrometer meters + PolicyStatsCollector (LongAdders → Redis shard every 5 s)
  → AuthorizationFilter → Controller
```
Diagrams: docs/03 (deployment, components), docs/07 ("Rate limiting over HTTP": sequence diagram and filter ordering).

### 1.3 Technology (exact versions in use)
| Area | Technology |
|---|---|
| Language / runtime | Java 17 (local JDK 17.0.18; containers `eclipse-temurin:17-jre`) |
| Framework | Spring Boot **3.5.16** (BOM-managed: Spring Security 6.5.11, Hibernate 6.6.53, Micrometer 1.15.12, Testcontainers 1.21.4) |
| Build | Maven wrapper (Maven 3.9.11), single module `com.utkarsh:distributed-rate-limiter:0.1.0-SNAPSHOT`, package `com.utkarsh.ratelimiter` |
| Rate-limit state | Redis 7.4 (`redis:7.4-alpine`), Lettuce client, Lua scripts via EVALSHA |
| Policy store | MySQL 8.4, Spring Data JPA, Flyway (V1–V3) |
| Security | Spring Security OAuth2 Resource Server (HS256 JWT) |
| Resilience | Resilience4j **2.4.0** (`resilience4j-circuitbreaker`, `resilience4j-micrometer`; 4 unrelated modules excluded) |
| API docs | springdoc-openapi **2.8.17** (dev profile only) |
| Observability | Micrometer + `micrometer-registry-prometheus`, Prometheus **v3.5.0**, Grafana **12.2.0**, `oliver006/redis_exporter:v1.78.0-alpine` |
| Load balancer | Nginx **1.28-alpine** |
| Load testing | k6 **2.3.0** (`grafana/k6:2.3.0` container; not installed on the host) |
| Tests | JUnit 5, Mockito, AssertJ, Awaitility, Spring Boot Test, Testcontainers |
| Host used | Apple M4, 10 cores, 16 GB; macOS 27.2; Docker Desktop 28.5.1 (VM: 10 CPUs, ~7.6 GiB) |

---

## 2. Completed work

### Phase 1 — Foundation & Architecture ✅ approved
Architecture proposal (docs/00), requirements, Maven/Spring Boot skeleton, profiles (base secure defaults, `dev`, `prod`, `test`), Redis + MySQL Compose services, Flyway V1 (schema with CHECK constraints) + V2 (tier seed policies), typed validated `ratelimiter.*` properties, health probes, docs 00–15 structure, git init.

### Phase 2 — Core Backend & Distributed Logic ✅ completed and approved
| Workstream | Delivered |
|---|---|
| **2A** Algorithms | `RateLimitAlgorithm` strategy + `AlgorithmRegistry`; Lua scripts `token_bucket.lua`, `fixed_window.lua`, `sliding_window.lua` (exact integer arithmetic, Redis `TIME`, single key per quota, rejections write nothing, TTL +1 s grace); `RateLimitKeyFactory` (`rl:{policyId:u|ip:subject}:tb|fw|sw`, unsafe subjects hashed); test-only `ManualScriptClock` |
| **2B** Policies & identity | `RateLimitPolicy` entity, CRUD API `/api/v1/policies` (ROLE_ADMIN, RFC 7807, optimistic locking, MySQL 3819/1062 mapping), compiled `PolicySnapshot` + `PolicyResolver`, `PolicyCache` (startup load, pub/sub invalidation after commit, 30 s reload, last-good on failure), deferred Flyway when MySQL is down, dev-only fallback policies, JWT (HS256, issuer, skew), dev-only demo-token + whoami endpoints (`@Profile("!prod")` + property), `ClientIdentityResolver` (tier never escalates), trusted-proxy IP via Tomcat RemoteIpValve |
| **2C** HTTP filter | `RateLimitFilter` after `BearerTokenAuthenticationFilter`, before authorization (counted once, servlet auto-registration disabled), headers, 429/503 JSON bodies (RFC 7807 + `error`/`message`/`retryAfterSeconds`), fail-open/fail-closed, demo endpoints, V3 endpoint-specific FAIL_CLOSED policy, script preloading before readiness |
| **2D** Circuit breaker | `CircuitBreakingScriptExecutor` (Resilience4j), `RedisFailureClassifier` (allow-list of infrastructure failures), strict 3-of-3 half-open probes, per-in-flight-probe 2 s timeout (R5 option B), readiness = initialisation + policies (R1), `dependencies` health group, pool-exhaustion handling (`PoolException` caught; pool mode really applied) |

### Phase 3A — Observability & Deployment ✅ implemented and verified, **awaiting approval**
| Part | Delivered |
|---|---|
| 3A.1 Metrics | `RateLimitTelemetry` (one record per decision; P1 hook; old filter constructor kept); 7 required meters + extras; histograms from 50 µs; Redis round-trip timer in `RedisTemplateScriptExecutor`; `/actuator/prometheus` (P2) |
| 3A.2 `/stats` | `PolicyStatsCollector` (monotonic LongAdders, watermark batching), exactly-once batches (boot id + seq) applied by `stats_apply.lua` via plain `EVAL`, per-instance shards, `GET /api/v1/policies/{id}/stats` |
| 3A.3 Deployment | `Dockerfile` (multi-stage, layered, non-root), `docker-compose.yml` (9 services + k6 profile), Nginx config, Prometheus config, readiness health checks, resource limits, `.env`-only secrets, **Redis command timeout 250 ms for the stack** (D42) |
| 3A.4 Grafana | Provisioned datasource + 14-panel dashboard (8 required), generated by `scripts/grafana/build_dashboard.py` |
| 3A.5 Smoke | `scripts/stack-smoke.sh` → `scripts/smoke/stack_smoke.py` (37 checks), `scripts/bench/mint-tokens.py`, k6 `smoke-concurrency.js`, `smoke-load.js`, `scripts/stack-up.sh` |

---

## 3. Tests and verification evidence

### 3.1 Automated build
`./mvnw clean verify` (last run 2026-11-11): **unit 158/158, integration 152/152 = 310 tests, BUILD SUCCESS.** Unit tests (`*Test`, Surefire) need no Docker; integration tests (`*IT`, Failsafe) use Testcontainers (Docker required). Runtime ~5–6 min.

| Phase | Tests added | Cumulative |
|---|---|---|
| Phase 1 | 15 | 15 |
| 2A | 100 | 115 |
| 2B | 73 | 188 |
| 2C | 42 | 230 |
| 2D | 58 | 288 |
| 3A | 22 | **310** |

The 288 tests that existed before 3A ran **unmodified** throughout 3A.

### 3.2 Key evidence (actual measurements; Apple M4, Docker Desktop)
| Claim | Evidence |
|---|---|
| Atomicity under concurrency | 1,000 simultaneous acquisitions, limit 100 → exactly 100, 5 rounds × 3 algorithms; 3 independent Redis clients → exactly 100. Negative control (GET-then-SET) admitted 1,000/1,000 |
| Lua = model | Differential test: 27,000 random steps, Lua equals an independent Java model on every field; 2 planted bugs caught |
| Distributed HTTP | 3 app instances, 300 concurrent requests, one identity, quota 50 → exactly 50 for TB, FW, SW; anonymous per-IP → exactly 10 |
| Policy propagation | Create/update/delete through different instances visible on all 3 via pub/sub; direct DB change picked up by the periodic reload |
| Circuit breaker | While OPEN: Redis EVALSHA count unchanged (10 → 10; manual Compose 34 → 34); in-process decision p50 8 µs / p99 19 µs; OPEN → CLOSED 5.18 s (test), 5.33 s (manual); exactly 3 of 50 concurrent half-open requests reached Redis; 3 instances recover independently |
| `/stats` exactly once | 16 threads × 50,000 records with random flush failures → exact; Redis paused during flushes (real ambiguous timeouts) → exact; 3 instances 7/11/13 → 31, raw Prometheus counters summed = 31 |
| **3A clean-state smoke** (run `20261111-091138`) | **PASSED 37/37 in 142 s**: 9 services healthy; timeout 250ms on app1–3; Nginx distribution 11/10/9; `/actuator/**` 404 via Nginx; client IP 172.28.0.3 keyed correctly, spoof ignored; no secret leaks; dashboard + 8 panels with data; **AC14 ×4: accepted=100 rejected=900, 0 fail-open, 0 breaker openings**; **AC18: k6 = Nginx log = raw counters of 3 instances = Prometheus = `/stats` = 57,391 allowed / 2,611 rejected / 0 fail-open** (60,002 requests, 0 dropped) |
| Redis server latency during smoke | `EVALSHA` p50 12 µs, p99 ≈ 61 µs, p99.9 117 µs, slow log empty |

Full tables: docs/10 (all test suites per phase, smoke results, earlier failing runs), docs/09, docs/12, docs/13. Smoke reports: `benchmarks/smoke/<run-id>/report.json` (**git-ignored**; see §9).

**No benchmark result exists yet.** All latency figures above are from functional or failure tests, not from the Phase 3B campaign.

---

## 4. Technical decisions and configuration values

### 4.1 Decisions (details: docs/04, docs/proposals/*)
| Id | Decision |
|---|---|
| D1 | Sliding Window **Counter** (not Log): O(1) memory; approximation documented (worst case → 2× limit spread over a window) |
| D2 | HS256 JWT; demo token endpoint dev-only |
| D3 / D4 | Global default FAIL_OPEN with per-policy override; fail-closed answers **503**, never 429 |
| D5 / D21 | MySQL down at startup: dev serves fallback policies; prod stays not-ready and retries (deferred Flyway, Hibernate boots without DB) |
| D6 | Redis `TIME` in scripts; `now` override only in test sources |
| D7 | Spring Boot 3.5.x on Java 17 |
| D9 / D40 | `/stats` via per-instance shards, exactly-once (boot, seq) batches |
| D14 | Application-default Redis command timeout **50 ms** (kept) |
| D16–D20 | Fixed window single hash key with window index; integer arithmetic; rejections write nothing; TTL = time-to-fresh + 1 s grace; algorithms return milliseconds |
| D22–D27 | Whole-set policy snapshot; data-free invalidation messages after commit; most-specific-match (one policy per request); tier never escalates; dev endpoints double-guarded; trusted proxies explicit, default none |
| D28–D32 | Filter after authentication / before authorization; RFC 7807 + contract fields; headers rounded up; fail-open sends no rate-limit headers; scripts preloaded before readiness |
| D33 (R1) | Readiness = `readinessState` + `policies`; `dependencies` group = `db`, `redis`, `rateLimitStore` |
| D34–D37 | Breaker at executor boundary (Resilience4j 2.4.0); strict 3-of-3; per-probe timeout (R5 B); only infrastructure failures count |
| D38 (P1) | Telemetry hook in the filter: observation only |
| D39 | Lua scripts read once with an explicit class loader, built from strings (fixed a stopped-class-loader bug and a per-call synchronized file check) |
| D41 | Stats flush uses plain `EVAL`: `EVALSHA` stays an independent count of rate-limit decisions |
| **D42** | **Redis command timeout 250 ms in the Compose deployment** (`STACK_REDIS_COMMAND_TIMEOUT`); app default stays 50 ms. Validated for this deployment only |
| P2–P8 (Phase 3 plan) | Prometheus endpoint on app port, blocked at Nginx; fixed container limits; offline token minting; dedicated `bench-*` policies; k6 inside the Compose network; stats semantics; two-part order with approval gate |

### 4.2 Configuration values
| Setting | Value | Where |
|---|---|---|
| `spring.data.redis.timeout` | **50ms** app default; **250ms** in Compose | `application.yml`; `docker-compose.yml` (`STACK_REDIS_COMMAND_TIMEOUT`) |
| Redis connect timeout | 2s | `application.yml` |
| Lettuce pooling | off (`REDIS_POOL_ENABLED`); when on, the shared connection is disabled | `application.yml`, `RedisPoolingConfiguration` |
| Tomcat threads | 200 (`TOMCAT_MAX_THREADS` in Compose); `mbeanregistry` enabled | |
| Hikari | pool 10, connection timeout 3000 ms | |
| `ratelimiter.key-prefix` | `rl` | |
| `ratelimiter.default-failure-mode` | `FAIL_OPEN` | |
| `ratelimiter.policy.reload-interval` | 30s | |
| `ratelimiter.policy.fallback-enabled` | false (dev: true; 5 fallback policies, ids 1000000001–1000000005) | |
| `ratelimiter.stats.flush-interval` | 5s (shard TTL 24 h) | |
| `ratelimiter.circuit-breaker.*` | window 20, minimum 10, 50 %, open 5s, 3 half-open probes, probe timeout 2s | |
| `ratelimiter.filter.excluded-paths` | `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/api/v1/auth/**`, `/error` | |
| `ratelimiter.filter.fail-closed-retry-after` | 5s | |
| JWT | issuer `distributed-rate-limiter`, clock skew 30s, `JWT_SECRET` base64 ≥ 32 bytes (startup fails otherwise) | |
| Dev endpoints | `ratelimiter.dev-endpoints.enabled` (dev only), max token TTL 1h | |
| Management | exposure `health,info,prometheus` (+ `flyway` in dev); readiness `readinessState,policies`; `dependencies` = `db,redis,rateLimitStore`; histogram for `http.server.requests` | |
| Rule bounds | limit 1–1,000,000; window 100 ms–24 h (keeps Lua arithmetic < 2^53) | `RateLimitRule`, DB CHECKs |
| Server | port 8080, graceful shutdown (20s), `forward-headers-strategy: native`, `internal-proxies=${TRUSTED_PROXIES:}` | |

### 4.3 Seed policies (Flyway)
| id | name | match | algorithm | limit / window | failure mode |
|---|---|---|---|---|---|
| 1 | anonymous-default | `/api/**`, tier ANONYMOUS, key IP | SLIDING_WINDOW | 10 / 60 s | default |
| 2 | free-tier | `/api/**`, FREE, key USER | TOKEN_BUCKET | 20 / 60 s (refill 20) | default |
| 3 | premium-tier | `/api/**`, PREMIUM, USER | TOKEN_BUCKET | 100 / 60 s | default |
| 4 | enterprise-tier | `/api/**`, ENTERPRISE, USER | TOKEN_BUCKET | 1000 / 60 s | default |
| 5 | demo-enterprise-endpoint | `GET /api/v1/demo/enterprise`, ENTERPRISE, USER | SLIDING_WINDOW | 50 / 60 s | FAIL_CLOSED |

---

## 5. Docker Compose deployment

### 5.1 Services (project name `ratelimiter`, network `ratelimiter-net` 172.28.0.0/24)
| Service | Address | Limits | Health check | Host port |
|---|---|---|---|---|
| nginx | 172.28.0.10 | 1.0 CPU / 128 MiB | `/nginx-health` | `${NGINX_HOST_PORT:-8080}` |
| app1 / app2 / app3 | .11 / .12 / .13 | 1.5 CPU / 1 GiB each | `/actuator/health/readiness` | none |
| redis | dynamic | 1.0 / 512 MiB | `redis-cli ping` | 127.0.0.1:6379 |
| mysql | dynamic | 0.5 / 512 MiB | `mysqladmin ping` | 127.0.0.1:3307 |
| redis-exporter | dynamic | 0.1 / 64 MiB | `/metrics` | none |
| prometheus | dynamic | 0.5 / 512 MiB | `/-/ready` | 127.0.0.1:9090 |
| grafana | dynamic | 0.25 / 256 MiB | `/api/health` | 127.0.0.1:3000 |
| k6 (profile `loadtest`) | dynamic | 2.0 / 1 GiB | — | none |

Only `app1` builds `ratelimiter-app:local`; `app2`/`app3` reuse it (`pull_policy: never`). Volumes: `mysql-data`, `prometheus-data`, `grafana-data`.

### 5.2 Commands
```bash
cp .env.example .env          # first time: set every change-me value (MySQL, JWT_SECRET, GRAFANA_ADMIN_PASSWORD)
```
```bash
scripts/stack-up.sh           # = docker compose up -d --build --wait, then status
```
```bash
docker compose ps
```
```bash
scripts/stack-smoke.sh --clean   # deletes this project's containers + volumes, rebuilds, runs all 37 checks
```
```bash
docker compose down           # stop, keep volumes
```
```bash
docker compose down -v        # stop and delete MySQL/Prometheus/Grafana data
```
```bash
docker compose up -d --wait redis mysql   # infrastructure only, for ./mvnw spring-boot:run (dev profile)
```
```bash
ADMIN=$(python3 scripts/bench/mint-tokens.py --print-admin)
```
```bash
curl -s localhost:8080/api/v1/policies/2/stats -H "Authorization: Bearer $ADMIN"
```
```bash
docker compose --profile loadtest run --rm k6 run /scripts/<script>.js
```
URLs: API `http://localhost:8080`, Grafana `http://localhost:3000` (admin / `.env`), Prometheus `http://localhost:9090`. Full guide: docs/14.

---

## 6. Repository structure
```
distributed-rate-limiter/
├── PROJECT_HANDOFF.md · PROJECT_PROGRESS.md · README.md
├── pom.xml · mvnw · mvnw.cmd · .mvn/wrapper/
├── Dockerfile · .dockerignore · docker-compose.yml · .env.example · .gitignore · .gitattributes
├── docs/
│   ├── 00-architecture-proposal.md … 15-interview-preparation.md   (11 and 15 still skeletons)
│   └── proposals/  2D-redis-circuit-breaker.md · phase-3-plan.md
├── infra/
│   ├── nginx/nginx.conf · prometheus/prometheus.yml · redis/redis.conf · mysql/ratelimiter.cnf
│   └── grafana/provisioning/{datasources,dashboards}/*.yml · grafana/dashboards/ratelimiter.json
├── load-tests/k6/  smoke-concurrency.js · smoke-load.js · .tokens/ (git-ignored)
├── scripts/
│   ├── stack-up.sh · stack-smoke.sh
│   ├── smoke/stack_smoke.py · bench/mint-tokens.py · grafana/build_dashboard.py
├── benchmarks/  smoke/<run-id>/ (git-ignored outputs) · results/ (empty; for 3B)
└── src/
    ├── main/java/com/utkarsh/ratelimiter/
    │   ├── algorithm/   strategy interface, 3 algorithms, registry, key/rule/result types
    │   ├── redis/       executors (Redis, circuit breaker), classifier, scripts, key factory, clocks, preloader
    │   ├── resilience/  FailureMode, breaker properties/config, rateLimitStore health
    │   ├── policy/      entity, repository, store, service, snapshot, cache, resolver, invalidation, health; api/ controller + DTOs
    │   ├── identity/    ClientIdentity, resolver, Tier
    │   ├── security/    security config, JWT/dev properties, demo token controller
    │   ├── filter/      RateLimitFilter, response writer, headers, properties
    │   ├── metrics/     RateLimitTelemetry, RateLimitOutcome
    │   ├── stats/       collector, Redis sink, keys, service, response
    │   ├── api/         ApiExceptionHandler · demo/ demo + whoami controllers · config/ wiring
    ├── main/resources/  application{,-dev,-prod}.yml · db/migration V1–V3 · scripts/*.lua (4)
    └── test/java/…      unit/ (22 classes) · script/ (5) · integration/ (9) · concurrency/ (1) · distributed/ (6) · support/ (8)
```

---

## 7. Phase 3B — Performance & Optimization (scope, not started)

Authoritative plan: `docs/proposals/phase-3-plan.md` (Phase 3B sections 3B.1–3B.7, acceptance 3B-AC1…AC16).

### 7.1 Parts
| Part | Content |
|---|---|
| **3B.1 Benchmark infrastructure** | `setup-policies.sh`, k6 library + scenario scripts, `run.sh`, `collect.py`, `docker-stats.sh`, `reconcile.py`, `report.py`, run artefact layout, automatic validity checks |
| 3B.2 Measurement method | Three latency views (limiter overhead histogram, Redis round trip client + server, E2E k6) + A/B with `ratelimiter.filter.enabled=false`; percentiles; four-way reconciliation; per-user enforcement bounds; resource collection |
| 3B.3 Workload model | Executors, VU sizing, request mixes, run phases |
| 3B.4 Campaign B0–B10 | below |
| 3B.5 Failure scenarios F1–F5 | below |
| 3B.6 Honesty rules | Invalid-run conditions, container limits, max sustainable throughput definition, bottleneck attribution |
| 3B.7 Optimisation | Evidence-based, one change at a time |

### 7.2 Benchmark scenarios
| Id | Scenario | Rates | Algorithms | Notes |
|---|---|---|---|---|
| B0 | Baseline | 100 RPS | TB, FW, SW | Floor latency |
| B1 | Load levels | 1,000 / 2,500 / 5,000 / 7,500 / **10,000** RPS | TB, FW, SW | Mixed profile, through Nginx. **10,000 RPS is a target only** |
| B2 | Stress / knee | ramp 1,000 → 15,000 | TB (others at the knee) | Max sustainable throughput |
| B3 | Overhead A/B | 1,000 and 5,000 | TB vs limiter disabled | E2E delta vs in-process histogram |
| B4 | Headroom vs throttled | 5,000 | TB, FW, SW | Allowed vs rejected path cost |
| B5 | Burst | 10,000 requests / 1 s | TB, FW, SW | Accepted vs bound, latency |
| B6 | Concurrency | 1,000 simultaneous | TB (capacity 100) | Exactly 100 across 3 instances |
| B7 | Distributed | 5,000 | TB, FW, SW | Through Nginx vs direct to instances |
| B8 | Hot key | 2,000 / 5,000 | TB, FW, SW | Single-key Redis behaviour |
| B9 | Pooling | 1,000 / 5,000 / knee | TB (all if different) | Pool off vs on (16; sweep 8/16/32 only if it helps) |
| B10 | Correctness under load | 2,000 | TB, FW, SW | Throttled profile, per-user bounds, raw k6 output |

### 7.3 Failure scenarios (during constant 2,000 RPS, mixed fail-open + fail-closed endpoints)
| Id | Fault | Measured |
|---|---|---|
| F1 | `docker compose pause redis` 20 s | Per-second status timeline, requests paying the timeout before OPEN, time to OPEN, fail-open 200s, fail-closed 503s, zero 429s, latency while OPEN, OPEN→HALF_OPEN→CLOSED per instance, recovery time |
| F2 | `docker compose restart redis` | Errors during restart, breaker transitions, NOSCRIPT→EVAL fallback, quota reset effect |
| F3 | `docker compose kill app2`, then `start` | Client-visible non-429 errors (Nginx passive failover), redistribution, rejoin after readiness, shared quota exact |
| F4 | `docker compose stop mysql` 60 s | No effect on limiting expected; readiness up; policy API 503 |
| F5 | Redis slowed (`DEBUG SLEEP` bursts) | Timeout-classified failures, breaker under partial degradation |

---

## 8. Benchmark methodology and acceptance criteria

### 8.1 Methodology (fixed by the approved plan)
- **Open model:** `constant-arrival-rate` for throughput (no coordinated omission); `ramping-arrival-rate` for the knee; `per-vu-iterations` (1,000 VUs × 1) only for the concurrency test; burst = 10,000/s for 1 s.
- **VU sizing:** VUs ≈ rate × latency, `preAllocatedVUs` 2–4× that; **`dropped_iterations > 0` invalidates a run**.
- **Request mixes:** headroom (10,000 users below limit), throttled (100 users ~5× over), mixed (80/20), hot key (1 user), anonymous (1 IP). Dedicated `bench-*` policies on `/api/v1/demo/profile`, identical limits for every algorithm. Tokens from `mint-tokens.py` (prod has no demo endpoint).
- **Run phases:** reset rate-limit state (`FLUSHDB`) → 60 s warm-up (tag `phase=warmup`; plus 2 min at 1,000 RPS before the first run of a session) → **180 s measure** (`phase=measure`; all k6 thresholds and Prometheus queries use only this window) → wait ≥ 10 s for the final scrape and ≥ 5 s for the stats flush → collect. **3 repetitions**; report median and min–max; flag spread > 10 %.
- **Three latency views kept separate:** limiter overhead `ratelimiter_request_duration_seconds`; Redis round trip `ratelimiter_redis_duration_seconds` (client) + `INFO latencystats` (server); E2E k6 `http_req_duration`. Plus the A/B delta (B3). Percentiles across instances only via `histogram_quantile(q, sum by (le) (...))`; never average percentiles.
- **Accepted vs rejected:** k6 = Nginx access log = Prometheus (after the final scrape) exactly; `/stats` = Prometheus after the final flush; HTTP 200 = allowed + fail-open. Independent extra source: Redis `cmdstat_evalsha` (D41).
- **Enforcement accuracy (per user, throttled runs):** TB accepted ≤ C + ⌊R·D⌋ + 1; FW and SW accepted ≤ L × windows touched. Target: zero over-admission.
- **Resources:** `docker stats` every 1 s; JVM CPU/heap/GC/Tomcat threads; Redis CPU (single-thread saturation), memory, ops/s, clients, `latencystats`, `SLOWLOG`, per-key `MEMORY USAGE`.

### 8.2 A run is INVALID if
`dropped_iterations > 0` or achieved < 99 % of target; k6 container CPU > 85 % in any 5 s window; unexpected statuses (not 200/429 or designed 503) > 0.1 %; counts don't reconcile; measurement overlapped warm-up or Redis was not reset. Invalid runs are recorded with the reason, never reported as results.

**Maximum sustainable throughput** = the highest tested rate that is valid, achieves ≥ 99 %, has non-429 errors < 0.1 %, E2E P99 < 100 ms and limiter-overhead P99 < 20 ms; found with B2 and confirmed with a constant-rate run.

**Laptop caveat:** everything shares one machine (the CPU allocation in §5.1 sums to ~9.85 of 10 VM CPUs). Results are relative and machine-specific; they do not predict production capacity.

### 8.3 Phase 3B acceptance criteria
| # | Criterion |
|---|---|
| 3B-AC1 | `run.sh` produces a complete artefact directory for a 1,000 RPS smoke run |
| 3B-AC2 | Validity checks run automatically and mark a run INVALID with the reason |
| 3B-AC3 | `reconcile.py` reports k6 vs Prometheus and `/stats` vs Prometheus within the reconciliation rules |
| 3B-AC4 | No hand-typed result: every number in docs/11 comes from `report.py` output |
| 3B-AC5 | Every B0–B10 scenario has 3 valid repetitions per configuration (or invalid runs recorded with reasons) |
| 3B-AC6 | All load levels attempted for all three algorithms; 10,000 RPS reported as achieved only if a valid run shows it |
| 3B-AC7 | Maximum sustainable throughput determined and confirmed |
| 3B-AC8 | Limiter overhead, Redis round trip and E2E latency at P50/P95/P99 reported separately, plus the A/B delta |
| 3B-AC9 | Zero over-admission (TB, FW, SW) in B10 per user; B6 exactly 100 of 1,000 |
| 3B-AC10 | Algorithm (B1, B4, B5, B8) and pooling (B9) comparison tables with target vs measured |
| 3B-AC11 | CPU, memory, JVM and Redis resources for every valid run |
| 3B-AC12 | F1–F5 each with a per-second timeline and the listed measurements |
| 3B-AC13 | Bottleneck at max sustainable throughput identified with saved evidence |
| 3B-AC14 | Every optimisation has before/after (3 runs each), kept or reverted with a reason |
| 3B-AC15 | No Lua, algorithm or security behaviour change without a reviewed proposal |
| 3B-AC16 | 3B docs updated and approved |

Requirement targets (unmeasured): 5,000 RPS throughput; limiter overhead avg < 5 / P95 < 10 / P99 < 20 ms; 100 % enforcement accuracy; 1,000+ concurrency; 3 instances; non-429 error rate < 0.1 %.

---

## 9. Known limitations and unresolved issues

### 9.1 Known limitations (by design or environment)
- Sliding Window Counter is approximate (worst case → 2× limit over a true window, spread out).
- Redis restart (persistence off) resets all quotas; replica failover can briefly over-admit (async replication); Sentinel/Cluster not deployed.
- Timed-out Redis commands may still execute later (consume quota, never over-admit).
- About 10 requests per instance pay the Redis timeout (50 ms app default, 250 ms in Compose) before the breaker opens; recovery takes the 5 s open wait plus 3 successful probes.
- Requests with invalid JWTs are rejected before the limiter (not rate limited). Tokens cannot be revoked before expiry; tier changes need a new token.
- IPv6 clients are limited per full address.
- One policy per request (no stacking).
- Pub/Sub invalidation is best-effort; worst-case policy staleness is 30 s.
- `/stats` loses at most one flush interval (5 s) of counts if an instance crashes.
- Nginx (open source) has passive health checks only.
- 250 ms Compose timeout is validated for this laptop deployment only (D42).
- In this Docker Desktop environment, host-originated requests reach Nginx with a non-local source address; the client-IP check therefore uses an in-network caller.
- Grafana screenshots are not saved automatically (no image renderer).

### 9.2 Unresolved issues / open items
| # | Item | Suggested handling |
|---|---|---|
| U1 | **No commits; project in a session-scoped folder** (§0) | Move/copy, then commit with approval |
| U2 | Phase 3A approval pending | Review before 3B |
| U3 | Smoke reports (`benchmarks/smoke/`) are git-ignored, so 3A evidence exists only locally (run `20261111-091138`) | Decide whether to commit that report as evidence (e.g. move to `benchmarks/evidence/3A/`) |
| U4 | `scripts/smoke/__pycache__/` is untracked and not git-ignored | Add `__pycache__/` to `.gitignore` before the first commit |
| U5 | Flyway logs "MySQL 8.4 is newer than this version of Flyway… latest supported 8.1" (Boot-managed Flyway); all migrations pass | Track; upgrade only with the Boot version |
| U6 | `matplotlib` not installed (Python 3.14.2) and needed by the planned `report.py` charts | Decide in 3B.1: local virtualenv + `pip install matplotlib` (needs approval), or dependency-free SVG/HTML charts |
| U7 | k6 container is limited to 2 CPUs; whether that sustains 10,000 RPS is unknown | The validity rule (k6 CPU ≤ 85 %) will detect it |
| U8 | Nginx logs every request to stdout (needed for reconciliation); its cost at high RPS is unmeasured | Measure in 3B; buffered logging is a 3B.7 candidate with evidence |
| U9 | docs/11 (benchmarks) and docs/15 (interview prep) are skeletons; docs/01 feature table still uses the original 8-phase numbering | 3B / Phase 4 |
| U10 | Mint-tokens default TTL is 1 h | Use `--ttl` longer than any benchmark session |
| U11 | Resource-backed `DefaultRedisScript` re-checked the script file under a lock on every call before D39; impact on earlier measurements unknown, now removed | Only 3B numbers count |

---

## 10. Exact next steps for 3B.1 — Benchmark Infrastructure

> Naming: in the plan, **3B.1** is the benchmark infrastructure; **B1** is the load-level scenario that runs on it. These steps cover 3B.1.

0. **Prerequisites:** 3A approved; repository committed or safely copied (U1); Docker Desktop running; `scripts/stack-smoke.sh --clean` green again on the target commit.
1. **Decide U6** (chart tooling) with the user.
2. **`scripts/bench/setup-policies.sh`:** create `bench-tb`, `bench-fw`, `bench-sw` (or one `bench` policy switched per run by PUT with `version`) on `GET /api/v1/demo/profile` for the benchmark tier, with identical limits; wait until all 3 instances apply them (probe `X-RateLimit-Limit` per `X-Upstream`, as in `stack_smoke.py::concurrency`); idempotent; delete on request.
3. **Tokens:** reuse `mint-tokens.py` (`--free N --hot M --ttl <session length>`); add groups if a mix needs them. Output stays in git-ignored `load-tests/k6/.tokens/`.
4. **`load-tests/k6/lib/common.js`:** load tokens (`SharedArray`), request helpers, tags `phase`/`scenario`/`algorithm`, counters `accepted`/`rejected`/`unexpected`, standard `handleSummary` writing to `/out/<run-id>/`.
5. **Scenario scripts** `load-tests/k6/b0.js … b10.js`, `f-load.js`: a warm-up scenario (60 s, `phase=warmup`) followed by a measure scenario (`startTime` = 60 s, 180 s, `phase=measure`); `constant-arrival-rate` with VU sizing from §8.1; thresholds only on `{phase:measure}` plus `dropped_iterations==0`; parameters by `__ENV` (RATE, DURATION, PROFILE, ALGORITHM).
6. **`scripts/bench/docker-stats.sh`:** sample `docker stats --no-stream --format json` every 1 s to CSV in the run directory; started and stopped by `run.sh`.
7. **`scripts/bench/run.sh <scenario> <algorithm> <rate> <rep>`:** check the stack is healthy → set the policy algorithm → `redis-cli FLUSHDB` → record Redis `INFO` (commandstats, latencystats, memory) → start the docker-stats sampler → record measure start/end timestamps → run k6 in the Compose network (`docker compose --profile loadtest run --rm k6 …`, k6 2.3.0) → wait ≥ 11 s (scrape) and ≥ 6 s (flush) → collect → validity → write `benchmarks/results/<date>-<scenario>-<algorithm>-<rps>-r<n>/` with `environment.txt` (hardware, Docker VM, image tags, JVM flags, app config incl. 250 ms timeout, git state).
8. **`scripts/bench/collect.py`** (stdlib only, like `stack_smoke.py`; reuse its `prom_query`, `raw_counters`, Nginx log parsing): Prometheus **range** queries for the measure window (requests by result/instance, overhead and Redis histograms → P50/P95/P99, JVM CPU/heap/GC, Tomcat threads, Redis CPU/memory/ops), raw per-instance counters before/after, `/stats` before/after, Nginx access-log counts (UA `k6/`, measure window), Redis `INFO` diff and `SLOWLOG`.
9. **`scripts/bench/reconcile.py`:** k6 = Nginx = raw counters summed = Prometheus (exact); `/stats` = raw counters (after flush); HTTP 200 = allowed + fail_open; Redis `cmdstat_evalsha` delta = executions counter delta; write `reconcile.json`.
10. **Validity (`validity.json`)**, implementing §8.2: dropped iterations, achieved rate ≥ 99 %, k6 CPU ≤ 85 % per 5 s window (from the docker-stats CSV), unexpected ≤ 0.1 %, reconciliation passed, warm-up excluded, Redis reset done.
11. **`scripts/bench/report.py`:** tables (and charts per U6) from `benchmarks/results/` only.
12. **Prove 3B-AC1…AC4** with a 1,000 RPS run (3 repetitions): complete artefacts, validity computed, reconciliation exact, report generated. Then stop for review before starting the B0–B10 / F1–F5 campaign.
13. Document in docs/10 (how to run) and docs/11 (method); update `PROJECT_PROGRESS.md`.

Constraints that still apply: no change to Lua, algorithms or security behaviour without a reviewed proposal (3B-AC15); existing 310 tests must stay green; no unmeasured performance claim; no commit/push without approval.

---

## 11. Files to inspect before making changes

| Area | Files |
|---|---|
| Plan and status | `PROJECT_PROGRESS.md`, `docs/proposals/phase-3-plan.md` (3B sections), `docs/04-design-decisions.md` (D1–D42) |
| Deployment | `docker-compose.yml` (limits, IPs, `STACK_REDIS_COMMAND_TIMEOUT`, k6 profile/volumes), `infra/nginx/nginx.conf`, `infra/prometheus/prometheus.yml`, `infra/redis/redis.conf`, `Dockerfile`, `.env.example` |
| Reusable tooling | `scripts/smoke/stack_smoke.py` (HTTP without proxies, Prometheus queries, raw counters, breaker state, Nginx log parsing, k6 invocation), `scripts/bench/mint-tokens.py`, `load-tests/k6/smoke-load.js`, `smoke-concurrency.js` |
| Metrics semantics | `metrics/RateLimitTelemetry.java`, `redis/RedisTemplateScriptExecutor.java`, `redis/CircuitBreakingScriptExecutor.java`, `stats/PolicyStatsCollector.java`, `docs/12-monitoring-and-observability.md`, `infra/grafana/dashboards/ratelimiter.json` (via `scripts/grafana/build_dashboard.py`) |
| Request path (do not change behaviour) | `filter/RateLimitFilter.java`, `security/SecurityConfiguration.java`, `policy/PolicySnapshot.java`, `src/main/resources/scripts/*.lua` |
| Configuration | `src/main/resources/application.yml`, `application-prod.yml`, `config/RateLimitAlgorithmConfiguration.java`, `resilience/CircuitBreakerProperties.java` |
| Evidence and method | `docs/10-testing-strategy.md`, `docs/11-performance-benchmarks.md` (skeleton with method rules), `docs/13-failure-handling.md`, `benchmarks/smoke/20261111-091138/report.json` (local only) |

---

## 12. Git status and uncommitted changes (2026-11-11)

- Branch: `main`; **no commits**; no remote configured.
- Index: a Phase 1 snapshot staged during Phase 1 (61 paths). Since then:
  - **28** staged files unchanged since staging (`A `);
  - **24** staged files modified afterwards (`AM`): `.env.example`, `.gitignore`, `PROJECT_PROGRESS.md`, `README.md`, `docker-compose.yml`, docs 02, 03, 04, 05, 06, 07, 08, 09, 10, 12, 13, 14, `pom.xml`, `RateLimiterProperties.java`, `application.yml`, `application-dev.yml`, `application-prod.yml`, `InfrastructureIT.java`, `application-test.yml`;
  - **9** staged `.gitkeep` placeholders deleted in the working tree (`AD`).
- **138 untracked paths**, including all Phase 2/3A source and tests, `Dockerfile`, `.dockerignore`, `infra/` contents, `scripts/`, `load-tests/k6/*.js`, `docs/proposals/`, `docs/11`, `docs/15`, `PROJECT_HANDOFF.md`, and `scripts/smoke/__pycache__/` (U4).
- Git-ignored and present locally: `.env` (real local secrets: never commit), `target/`, `benchmarks/smoke/` (smoke evidence, U3), `load-tests/k6/.tokens/` (bearer tokens).
- Suggested first commit (requires approval): add `__pycache__/` to `.gitignore`, `git add -A`, check that `git status` lists no `.env`, `.tokens` or `target`, then commit with the configured attribution trailer.
