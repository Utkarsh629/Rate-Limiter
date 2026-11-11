# 10 — Testing Strategy

> Status: **Phase 1, Phase 2 algorithms, policy management & identity, Phase 2 HTTP filter integration and Phase 2 circuit-breaker tests implemented and passing** (288 tests). Later workstreams add their own sections.

## Layout and commands

| Kind | Naming | Runner | Needs Docker | Command |
|---|---|---|---|---|
| Unit | `*Test` | Surefire | no | `./mvnw test` |
| Integration | `*IT` | Failsafe | yes (Testcontainers) | `./mvnw verify` |

Test packages: `unit`, `script`, `integration`, `concurrency`, `distributed`; shared fixtures in `support`.
`support.InfrastructureContainers` starts `redis:7.4-alpine` and `mysql:8.4` (the same images as Compose) and
wires them in with `@ServiceConnection`.

## Phase 1 test cases

| Test | Verifies | Expected | Actual (2026-11-10) |
|---|---|---|---|
| `RateLimiterPropertiesTest.appliesSafeDefaults` | Defaults: prefix `rl`, FAIL_OPEN, 30 s reload, 5 s flush, fallback off | pass | pass |
| `RateLimiterPropertiesTest.bindsOverrides` | Overrides bind | pass | pass |
| `RateLimiterPropertiesTest.rejectsMissingInstanceId` | Startup fails without instance id | context fails | pass |
| `RateLimiterPropertiesTest.rejectsInstanceIdThatWouldBreakRedisKeys` | Rejects `:`, `{`, spaces | context fails | pass |
| `RateLimiterPropertiesTest.rejectsSubSecondStatsFlushInterval` | Flush ≥ 1 s | context fails | pass |
| `RateLimiterPropertiesTest.rejectsUnknownFailureMode` | Enum validation | context fails | pass |
| `InfrastructureIT.redisAnswersPing` | Redis connectivity | PONG | pass |
| `InfrastructureIT.redisRunsLuaScriptsThatReadServerTime` | Lua + `TIME` available (needed for Phase 2) | within 60 s of host clock | pass |
| `InfrastructureIT.mysqlIsReachableAndIsVersion84` | MySQL connectivity | 8.4.x | pass |
| `InfrastructureIT.flywayAppliedBothMigrationsSuccessfully` | Flyway | V1, V2 SUCCESS, none pending | pass |
| `InfrastructureIT.seedPoliciesExistForEveryTier` | Seed data | 4 tier policies | pass |
| `InfrastructureIT.schemaRejectsNonPositiveLimit` | `chk_policy_limit` | MySQL error 3819 | pass |
| `InfrastructureIT.schemaRejectsRefillTokensOnNonTokenBucketPolicy` | `chk_policy_refill` | MySQL error 3819 | pass |
| `InfrastructureIT.schemaRejectsDuplicatePolicyName` | Unique name | DataIntegrityViolation | pass |
| `InfrastructureIT.readinessProbeIsUpWhenRedisAndMysqlAreUp` | Readiness group | 200, db + redis UP | pass |

Result: `./mvnw verify` → unit 6/6, integration 9/9, BUILD SUCCESS.

## Phase 1 manual checks (against Docker Compose)

| Check | Result |
|---|---|
| `docker compose up -d --wait` | redis and mysql healthy |
| Redis `CONFIG GET` maxmemory-policy / appendonly / save | `volatile-ttl` / `no` / empty |
| App start (dev profile) | started in 2.15 s; Flyway "Successfully applied 2 migrations … now at version v2" |
| `/actuator/health/readiness` | 200, db UP, redis UP (7.4.12) |
| `/actuator/flyway` | V1, V2 SUCCESS |
| Redis stopped → readiness / liveness | 503 / 200 *(superseded by R1 in 2D: readiness now stays 200; see the 2D section)* |
| Redis restarted → readiness | 200 |
| SIGTERM | "Graceful shutdown complete" |

## Phase 2 test suites — algorithm foundation

| Suite | Kind | Tests | Covers |
|---|---|---|---|
| `RateLimitRuleTest` | unit | 5 | Factories, bounds, refill only for token bucket, 2^53 bound |
| `RateLimitResultTest` | unit | 2 | Result invariants (allowed ⇒ retry 0, rejected ⇒ retry > 0, remaining ≤ limit) |
| `RateLimitKeyFactoryTest` | unit | 5 | Key format, hash tags, IPv6, hashing of unsafe/long subjects, validation |
| `AlgorithmRegistryTest` | unit | 3 | Strategy dispatch, missing / duplicate implementation fails fast |
| `LuaRateLimitAlgorithmTest` | unit (mocked executor) | 6 | Script arguments per algorithm, empty clock arg in production, reply mapping, malformed replies, rule/algorithm mismatch |
| `RedisTemplateScriptExecutorTest` | unit (mocked template) | 4 | Timeout/connection errors → `RateLimitStoreException`, null/non-integer replies, scripts load from classpath |
| `TokenBucketScriptIT` | Redis, test clock | 15 | Initially full, consumption, empty rejection, partial and complete refill, cap at capacity, exact retry, 20/min exactness, slow refill, capacity 1, rejection writes nothing, clock moving back, key isolation, TTL, real-time expiry and refill with Redis TIME |
| `FixedWindowScriptIT` | Redis, test clock | 15 | Initial request, increment, limit reached, exact retry, next window, skipped windows, boundary burst (2×L in 2 ms), clock moving back, limit 1, rejection writes nothing, TTL, early-expiry regression, real-time expiry and roll-over |
| `SlidingWindowScriptIT` | Redis, test clock | 22 | Current-only counting, weighted count, exact retry (both branches), linear decay table (8 cases), transitions, skipped windows, smoothed boundary, documented approximation (19 in one true window), clock moving back, limit 1, rejection writes nothing, TTL, real-time expiry and recovery |
| `ScriptDifferentialIT` | Redis, randomized | 9 | Lua vs independent Java model, 3,000 random steps × 3 seeds × 3 algorithms |
| `ConcurrentAcquisitionIT` | Redis, concurrency | 8 | 1,000 simultaneous / limit 100 → exactly 100 (×3 algorithms × 5 rounds), Redis TIME variant, 3 independent clients, unsafe negative control |
| `AlgorithmWiringIT` | Spring context | 6 | Registry holds one production bean per algorithm; enforcement through Spring wiring with Redis TIME; TTL present |

Result (2026-11-10): `./mvnw clean verify` → unit 31/31, integration 84/84, BUILD SUCCESS.

### Expiration testing approach
- **Logical expiration** (window roll-over, refill) uses `ManualScriptClock`, so it is exact and fast.
- **Real expiration** (the key actually disappears) uses Redis `TIME` and short windows, waiting with Awaitility, not fixed sleeps.

### Mutation check of the differential test
Done once, manually (not part of the build). Each planted bug was run against `ScriptDifferentialIT`, and the script was then restored:
| Mutation | Result |
|---|---|
| Sliding window `t < left` → `t <= left` | Survived. **Equivalent mutant:** at `t = left` both branches give the same retry |
| Sliding window retry `(L − c − 1)` → `(L − c)` | Killed by 3/3 seeds |
| Token bucket refill off by one ms | Killed by 3/3 seeds |

## Phase 2 test suites — policy management & identity

| Suite | Kind | Tests | Covers |
|---|---|---|---|
| `PolicySnapshotTest` | unit | 10 | Resolution precedence truth table (tier, path specificity, `{id}` vs `**`, method, priority, id), no match, empty snapshot |
| `ResolvedPolicyTest` | unit | 4 | Compilation (refill default, invalid pattern/method/limits), USER vs IP quota keys, identity invariants |
| `PolicyCacheTest` | unit (mock source) | 6 | Startup load; prod stays uninitialised when DB down; dev fallback then recovery to DATABASE; failed reload keeps last good; periodic reload; reload coalescing |
| `ClientIdentityResolverTest` | unit | 10 | Anonymous by remote address; JWT subject + tier; tier claim never escalates (missing/unknown/ANONYMOUS → FREE); X-Forwarded-For never read directly |
| `PolicyValidationTest` | unit | 4 | Bean Validation ranges mirror CHECK constraints; malformed fields; refill only for token bucket |
| `PersistenceErrorTranslationTest` | unit | 6 | 3819 → `InvalidPolicyException` with field; 1062 → conflict; other errors unchanged; connection-failure detection; Flyway deferral and retry; broken migrations still fail |
| `PolicyApiIT` | HTTP, MySQL, Redis | 11 | CRUD contract (201 + Location, 200, 204, 404), snapshot refresh after create/disable, field errors, refill rule, bad pattern, bad enum, page bound, duplicate 409, optimistic locking (idempotent no-op PUT, stale version 409, missing version 400), auth (none/expired/wrong key/wrong issuer/garbage → 401, non-admin → 403) |
| `PolicyConstraintIT` | MySQL | 4 | Entities bypassing validation: CHECK 3819 on limit, window, refill → 400-type exception naming the field; duplicate 1062 → conflict |
| `IdentityAndResolutionIT` | HTTP, MySQL, Redis | 9 | Anonymous → ANONYMOUS tier, IP key; each tier → its seeded policy, user key; tier escalation blocked; invalid token not downgraded; spoofed XFF ignored; demo token works and validates input; resolved target drives `AlgorithmRegistry` (FREE: 20 allowed, then rejected); specific endpoint policy overrides tier default |
| `TrustedProxyIT` | HTTP (own context) | 3 | With loopback trusted: client IP = right-most untrusted XFF hop; spoofed left entries and trusted hops ignored |
| `DevEndpointsDisabledIT` | HTTP (2 contexts) | 2 | Demo token, whoami and OpenAPI absent when disabled, and absent under `prod` even with the property forced on |
| `StartupWithoutMySqlIT` | 2 real app contexts | 2 | Prod: starts NOT READY (503/200), then MySQL started on the configured port → deferred migration, 4 policies, READY with no restart. Dev: fallback policies served |
| `PolicyPropagationIT` | 3 + 2 real app contexts | 2 | Create/update/delete via different instances visible on all three via pub/sub (reload interval 10 min, so pub/sub is the only path); direct DB change picked up by periodic reload (1 s) on both instances |

Result (2026-11-10): `./mvnw clean verify` → unit 71/71, integration 117/117 (188 total), BUILD SUCCESS.

### Multi-instance test design
`PolicyPropagationIT` and `StartupWithoutMySqlIT` start full application contexts with
`SpringApplicationBuilder`, each with its own Tomcat, Hikari pool, Lettuce connections and Redis
listener container, all sharing one MySQL and one Redis container. Connection settings are passed
as command-line arguments. Builder *default* properties have the lowest precedence and
`application.yml` would override them: an early version of these tests did exactly that and
attempted a login against a MySQL on `localhost:3306`.

### Manual verification (dev profile against Docker Compose)
Readiness with `policies` UP (source DATABASE, 4 policies); demo token; create → 201 + Location;
validation 400 and duplicate 409 as problem+json; 401 without a token, 403 with a non-admin token;
whoami resolves the more specific `POST /api/v1/orders/**` policy for PREMIUM; spoofed
`X-Forwarded-For` ignored; OpenAPI lists all four paths; delete → 204.

## Phase 2 test suites — HTTP filter integration (2C)

| Suite | Kind | Tests | Covers |
|---|---|---|---|
| `RateLimitFilterTest` | unit (mock servlet, stub algorithms) | 23 | Allowed → headers + chain; anonymous keyed by IP; rejected → 429 + headers + JSON, chain not called; Retry-After rounding (6 cases); header conversions; excluded paths bypass (6 cases); no policy → no headers; filter disabled; Redis failure: FAIL_OPEN continues without headers, FAIL_CLOSED policy → 503 (not 429), global FAIL_CLOSED default, policy FAIL_OPEN override; unexpected exceptions not swallowed |
| `RateLimitFilterIT` | HTTP, MySQL, Redis | 14 | Anonymous per-IP quota (10) with full 429 body; spoofed XFF gives no new quota; FREE 20 / PREMIUM 100 / ENTERPRISE 1000 exact quotas with Remaining counting down; endpoint policy beats tier default; independent users; token-bucket and fixed-window header semantics; excluded endpoints unlimited without headers; ordering: token identity used, 401/403 still counted, invalid token rejected before limiter; each request counted once (404 error dispatch) |
| `DistributedHttpRateLimitIT` | 3 app instances, HTTP | 4 | 300 concurrent requests from one identity across 3 instances → exactly 50 accepted for each algorithm; anonymous per-IP quota shared across instances (exactly 10) |
| `FailureModeIT` | 2 app instances, HTTP | 1 | Redis paused mid-traffic: FAIL_OPEN → 200 without headers; FAIL_CLOSED policy → 503 + Retry-After + JSON; global FAIL_CLOSED → 503; bounded latency (~55–58 ms); recovery after unpause with no restart |

Result (2026-11-10): `./mvnw clean verify` → unit 94/94, integration 136/136 (230 total), BUILD SUCCESS.

### Deterministic quota tests
The seeded tier policies refill continuously (PREMIUM gains a token every 600 ms), so "exactly 100
then 429" would depend on test speed. Tier quota tests temporarily set the policy's refill to 1 token
per day (capacity, which is the quota, unchanged) and restore it afterwards. Each HTTP test starts
from an empty Redis (`ApiTestSupport` flushes it), because all anonymous test traffic shares the
loopback IP.

### Found by these tests
- **Cold-start fail-open:** the first rate-limit call on a fresh instance (connection setup, class
  loading, `EVALSHA → NOSCRIPT → EVAL`) could exceed the 50 ms timeout and fail open, intermittently.
  Fixed by `RateLimitScriptPreloader`, which runs `SCRIPT LOAD` before the instance reports ready.
  `FailureModeIT` was then run 3 times in a row, all passing.
- **Timeouts don't cancel Redis commands** (found in the manual run): see docs/09.

## Phase 2 test suites — Redis circuit breaker (2D)

| Suite | Kind | Tests | Covers |
|---|---|---|---|
| `RedisFailureClassifierTest` | unit | 15 | Timeouts, connection failures, pool exhaustion (incl. wrapped), 7 "cannot serve" Redis replies → infrastructure; Lua runtime error, WRONGTYPE, argument errors, NOSCRIPT, malformed replies, unknown exceptions → ignored |
| `CircuitBreakingScriptExecutorTest` | unit, real Resilience4j, fake probe clock | 16 | Stays closed below minimum calls; opens at 50 %; 429 results are successes; programming errors never trip and still propagate; OPEN never calls Redis; 3 of 3 probes close (2 not enough); failing 3rd probe reopens where the library alone would close; failed probe restarts the wait; probe with programming error returns its permit; quiet traffic does not reopen (R5); hung probe reopens and its late success is not recorded; slow probe reopens on completion; exactly 3 of 100 concurrent requests probe; 50 rounds of concurrent probes with one failure never close; failure storm bounded (≤ 60 of 1,000 reach Redis); error and binding meters |
| `CircuitBreakerWiringTest` | unit (context runner) | 3 | Breaker wraps the executor by default with approved values; disabled = direct Redis calls; inconsistent settings rejected |
| `RateLimitFilterTest` (+16) | unit | 39 | Matrix: {CLOSED infra error, OPEN refused, HALF_OPEN refused, HALF_OPEN probe failed} × {policy FAIL_OPEN, policy FAIL_CLOSED, default FAIL_OPEN, default FAIL_CLOSED}: status, never 429, Retry-After 5, no rate-limit headers, controller reached or not |
| `CircuitBreakerIT` | 1–2 app instances, real Redis paused/unpaused | 6 | Outage → OPEN; readiness 200 + dependencies 503; no EVALSHA while OPEN (Redis INFO); decision latency while OPEN; fail-open / fail-closed over HTTP; recovery with transition sequence; failed probe reopens and restarts wait; exactly 3 of 50 concurrent requests reach Redis at half-open; NOSCRIPT after script-cache loss handled without tripping; pool exhaustion (pool size 1) → OPEN; MySQL stopped at runtime → readiness 200, limiting continues |
| `DistributedHttpRateLimitIT` (+1) | 3 app instances | 5 | Breakers open and recover independently; shared quota exact (50/300) after recovery |
| `InfrastructureIT` (rewritten test, +1) | | 10 | Readiness = readinessState + policies; dependencies group shows db, redis, rateLimitStore |
| `StartupWithoutMySqlIT` (updated) | | 2 | Dev fallback: readiness 200, dependencies 503 (was 503 / n/a before R1) |

Result (2026-11-10): `./mvnw clean verify` → unit 144/144, integration 144/144 (**288 total**), BUILD SUCCESS.

### Mutation checks on the breaker tests (manual, not part of the build)
| Planted change | Result |
|---|---|
| Failed probe no longer forces OPEN (library rule only) | Caught by 3 tests |
| Results of guard-expired probes recorded | Survived: **equivalent**, because the age rule independently blocks any result older than 2 s |
| Age rule removed | Caught by `aProbeThatTakesLongerThanTheTimeoutReopensOnCompletion` |

### Measured in `CircuitBreakerIT` (clean run)
9 requests at 53–59 ms before OPEN (1 prior success recorded); while OPEN: in-process decision p50 8 µs / p99 19 µs, HTTP p50 0.84 ms / p99 1.6 ms; EVALSHA 10 → 10; OPEN → CLOSED 5.18 s; 3 of 50 concurrent requests probed Redis; pool mode: 23 pool errors, 1 timeout, 16 refusals → OPEN.

## Phase 3A test suites — observability & deployment

| Suite | Kind | Tests | Covers |
|---|---|---|---|
| `RateLimitTelemetryTest` | unit (Prometheus registry) | 4 | All outcomes recorded with bounded tags; histogram first bucket ≤ 50 µs; series count identical after 1 and 500 policies; hand-off to stats |
| `RateLimitFilterTelemetryTest` | unit | 5 | Each decision recorded exactly once with the right result (4 outcomes); responses identical with and without telemetry; undecided and excluded requests not recorded |
| `PolicyStatsCollectorTest` | unit | 5 | Batches only new increments; failed batch retried unchanged; ambiguous failure not double-counted; **16 threads × 50,000 records concurrent with a flusher whose sink randomly succeeds, fails before applying, or applies then fails → totals exact**; stop flushes |
| `StatsApplyScriptIT` | real Redis | 3 | At-most-once per (boot, seq); restart accepted; TTL and index; **Redis paused during a flush (client 50 ms timeout, command executes later): totals exact** |
| `ObservabilityIT` | Spring + Testcontainers | 4 | `/actuator/prometheus` without token; all required meters; label keys bounded; histogram range; **counters reconcile exactly** with 23 HTTP requests (20 allowed, 3 rejected); `/stats` exact for a dedicated policy; 401/403/404 |
| `StatsDistributedIT` | 3 app instances | 1 | `/stats` per instance 7/11/13 and total 31 exact; **raw `/actuator/prometheus` counters of the 3 instances summed = 31**; Redis paused across ≥ 2 flushes with fail-open traffic → exact totals afterwards (15 fail-open) and unchanged after further flushes |

Result (2026-11-11): `./mvnw clean verify` → unit 158/158, integration 152/152 (**310 total**: the 288 earlier tests unchanged + 22 new), BUILD SUCCESS.

### Found while building 3A
- Resource-backed Lua scripts captured a stopped web-app class loader (88 integration tests errored until fixed: D39).
- The stats flush first used `EVALSHA` and polluted `CircuitBreakerIT`'s Redis command count (fixed by D41, without changing that test).
- Test bugs, fixed in the tests themselves: `@SpringBootTest` disables Prometheus export unless `@AutoConfigureObservability`; admin `/stats` polls are themselves rate-limited requests and were inflating a raw counter delta.

## Phase 3A smoke test (`scripts/stack-smoke.sh`)

One command against the full Compose stack; `--clean` first deletes this project's containers and volumes. Report: `benchmarks/smoke/<run-id>/report.json`. It is a functional check; no latency or throughput number from it is a result.

### Clean-state run 20261111-091138 — PASSED (37/37 checks, 142 s)
| AC | Check | Result |
|---|---|---|
| AC9 | All 9 services healthy; app health checks use readiness | ✅ |
| D42 | Redis command timeout on app1/app2/app3 | ✅ 250ms / 250ms / 250ms |
| AC10 | 30 requests through Nginx | ✅ 11 / 10 / 9 across the 3 instances |
| AC12 | `/actuator/health`, `/readiness`, `/prometheus` via Nginx; Prometheus targets | ✅ 404 ×3; app-1, app-2, app-3, redis up |
| AC11 | Caller container 172.28.0.3 → key `rl:{1:ip:172.28.0.3}:sw`; with `X-Forwarded-For: 6.6.6.6` | ✅ same single key |
| AC13 | 4 secret values vs 18 files and the image | ✅ none found |
| AC15 | Dashboard and datasource | ✅ API 200, 14 panels, datasource healthy |
| AC14 | 1,000 concurrent requests, one identity, capacity 100 (refill 1/day), **4 rounds** | ✅ each round: accepted=100 rejected=900 unexpected=0, 0 fail-open, 0 breaker openings, all breakers closed before and after |
| AC18 | 60 s at 1,000 req/s (constant arrival rate), 60,002 requests | ✅ 0 dropped iterations, 0 unexpected |
| AC18 | k6 / Nginx log / raw counters (3 instances) / Prometheus / `/stats` | ✅ all **57,391 allowed, 2,611 rejected, 0 fail-open** (per instance: 19,136/864, 19,139/862, 19,116/885) |
| AC16, AC17 | 8 required panels return data; percentile queries `sum by (le)` | ✅ |

### Earlier runs (kept as evidence; Redis command timeout 50 ms)
| Run | Result |
|---|---|
| 20261111-001753 (clean, cold) | AC14 failed: 371 accepted; 272 fail-open (79 timeouts + 193 breaker refusals) |
| 20261111-001933, -002034 | AC14 failed: 573 and 865 accepted; AC18 counts reconciled exactly (57,382 allowed + 8 fail-open = 57,390 k6 accepted) |
| Diagnostic bursts (4 rounds each) | 50 ms: 425–909 fail-open per round; 50 ms + 32 threads: 285–841; **250 ms: 100/900 ×4, 0 fail-open; 500 ms: 100/900 ×4, 0 fail-open** (decision D42) |

## Planned (later workstreams)
Algorithm script tests, concurrency tests (1,000 threads, start-gate latch, repeated), policy resolution
truth table, filter/HTTP tests, Redis failure tests (container stop/pause), 3-context distributed test,
3-container k6 correctness test.
