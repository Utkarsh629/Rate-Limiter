# 04 — Design Decisions

> Status: **decisions D1–D12 approved at the end of Phase 0**, D13–D15 recorded in Phase 1, D16–D20 in Phase 2 (algorithms), D21–D27 in Phase 2 (policy management & identity), D28–D32 in Phase 2 (HTTP filter), D33–D37 in Phase 2 (circuit breaker, 2D), D38–D42 in Phase 3A, D43–D50 in Phase 3B, D51 in Phase 4. Each entry follows: problem → decision → alternatives → trade-offs → scalability. D43–D50 record the Phase 3B benchmark and release decisions (approved).

## D1. Sliding Window Counter, not Sliding Window Log
- **Problem:** a sliding window needs per-client history.
- **Decision:** Sliding Window Counter: one hash per client holding the current count, the previous window's count and the current window's start time. The estimate is `prev × (1 − elapsed/W) + curr`.
- **Alternatives:** Sliding Window Log (a sorted set of request timestamps).
- **Trade-offs:** The counter is O(1) memory per client but approximate, because it assumes requests in the previous window were evenly spread. The log is exact but stores up to `limit` entries per client.
- **Scalability:** at 100k clients × 1,000/min, the log needs gigabytes; the counter needs a few MB.

## D2. JWT identity with a development-only token endpoint
- **Problem:** the limiter needs a user id and tier for every request without a database lookup.
- **Decision:** HS256 JWTs validated by Spring Security's resource server (signature + expiry). The secret comes from `JWT_SECRET`. A token-minting endpoint exists only in the `dev` profile.
- **Alternatives:** API keys in MySQL (a lookup per request, plus a cache).
- **Trade-offs:** tier changes take effect when the token is re-issued. Revocation is not supported before expiry.

## D3. Fail-open by default, per-policy override
- **Decision:** global default `FAIL_OPEN`; each policy may set `FAIL_CLOSED` (login, OTP, payments).
- **Trade-off:** availability over protection by default. During a Redis outage, limits on fail-open policies are not enforced.

## D4. Fail-closed returns 503, not 429
- **Reason:** 429 tells the client it exceeded its quota. During a Redis failure that is false, and clients may back off for the wrong reason. A 503 with `Retry-After` describes the actual state.

## D5. MySQL unavailable at startup
- **Decision:** In development (`ratelimiter.policy.fallback-enabled=true`), the app starts with policies defined in configuration. In production (`false`), it does **not** serve fallbacks. It keeps retrying the load, and readiness stays DOWN until a policy snapshot has been loaded from MySQL.
- **Reason:** silently enforcing stale or default limits in production is worse than an instance the load balancer does not route to.
- **Implemented:** the property exists with these defaults (Phase 1); the readiness indicator and fallback loading come in Phase 3.

## D6. Redis `TIME` inside scripts; `now` override for tests only
- **Problem:** three instances have three clocks; skew corrupts window boundaries and refill math.
- **Decision:** production scripts read `redis.call('TIME')`. An optional `now` argument exists only on internal script-testing interfaces, so tests can be deterministic (for example, reproducing the fixed-window boundary burst). It is never reachable from HTTP or production request handling.

## D7. Spring Boot 3.5.16, Java 17
- 3.5.x is the final 3.x line. Pinned to 3.5.16, the latest 3.5 patch on Maven Central when Phase 1 was built. The parent BOM manages every other dependency version. Migrating to Spring Boot 4 is a documented future step.

## D8. Redis is authoritative; atomic operations only
- All quota state lives in Redis. Every read-modify-write is a single Lua script, and expiry is set inside the same script. Instances hold no authoritative counters.

## D9. Per-instance statistics shards (refines Phase 0 §P6)
- **Problem:** `/policies/{id}/stats` needs cluster-wide allowed/rejected counts. A single per-policy Redis key incremented on every request is a hot key.
- **Decision:** each instance counts in memory (`LongAdder`) and every **5 s** flushes the increments to **its own** shard key, `rl:stats:<policyId>:<instanceId>`. The stats endpoint sums the shards.
- **Trade-offs:** stats lag by up to 5 s, and counts not yet flushed are lost if an instance crashes. These counts are telemetry, not enforcement. Enforcement never depends on them.

## D10. Separate correctness metrics from performance metrics
- Correctness = exact accepted/rejected counts under concurrency. Performance = latency and throughput. They are reported in separate tables and never combined into one "accuracy under load" number.

## D11. Measure limiter overhead independently of HTTP latency
- An in-process Micrometer timer covers only the rate-limit decision (policy resolution + Redis script + decision mapping). k6 measures end-to-end latency. Both are reported side by side.

## D12. k6 fixed-arrival-rate for throughput
- k6's `constant-arrival-rate` executor (open model) avoids coordinated omission. Closed-model tests with a fixed number of virtual users are used only for the "N simultaneous requests" correctness tests.

## D13. Lettuce without pooling by default (Phase 1)
- Lettuce multiplexes commands over one thread-safe connection. `commons-pool2` is on the classpath, so pooling can be switched on with `REDIS_POOL_ENABLED=true`. It stays off; the Phase 3B benchmark (B9) showed a 16-connection pool did not help (D48).

## D14. Short Redis command timeout (Phase 1)
- `spring.data.redis.timeout=50ms` (env `REDIS_COMMAND_TIMEOUT`). The limiter is on every request, so a slow Redis must hand over to the failure policy quickly rather than hold Tomcat threads. The 2 s connect timeout is separate and only affects (re)connection.

## D15. Schema constraints duplicate API validation (Phase 1)
- MySQL `CHECK` constraints reject impossible policies even if they bypass the API. MySQL reports violations as error 3819 (SQLState `HY000`), which Spring does **not** translate to `DataIntegrityViolationException`. The Phase 3 service layer must validate first and map 3819 explicitly if it can still occur.

## D16. Fixed window stores the window index in one hash key (Phase 2, refines Phase 0 §4.1)
- **Problem:** the proposal used one key per window (`…:fw:<windowIndex>`), but the index is derived from Redis `TIME` inside the script. Building key names inside a script breaks Redis Cluster's rule that scripts declare their keys in `KEYS`.
- **Decision:** one hash per (policy, subject) with `w` (window index) and `c` (count). A count from an older window is ignored.
- **Trade-off:** one extra hash field; in exchange the script stays single-key and Cluster-safe, and correctness doesn't depend on TTL timing.

## D17. Exact integer arithmetic in scripts (Phase 2)
- Token bucket stores tokens × period ("units"), so refill is an integer number of units per ms. The sliding window compares `p·(W−e) + (c+1)·W ≤ L·W` instead of a fractional estimate.
- Rule bounds (`limit ≤ 10^6`, `window ≤ 24 h`) keep every product below 2^53, so Lua's doubles hold them exactly.
- **Why:** with floats, 20 tokens / 60 s has no exact representation, and retry times could be off by a millisecond. Tests assert retry timing to the millisecond.

## D18. Rejected requests write nothing (Phase 2)
- In all three algorithms, the state after a rejection is a pure function of the stored state and the current time. Skipping the write saves a Redis write per rejection, and abusive clients can't keep extending their key's TTL.

## D19. TTL = time until state is equivalent to fresh, + 1 s grace (Phase 2)
- A missing key means fresh state, so keys can expire once they would be fresh, but never earlier. An early expiry would over-admit. Tests caught this: Redis evaluates `PEXPIRE` against the time cached at script start, while `TIME` reads the live clock, so an exact TTL could fire about 1 ms early. Every TTL therefore gets +1 s grace.

## D20. Algorithms return milliseconds; HTTP rounding belongs to the web layer (Phase 2)
- `RateLimitResult` carries `retryAfterMillis` and `resetAfterMillis`. Rounding to whole seconds for `Retry-After` (rounded up, minimum 1) happens in the Phase 4 response writer, keeping algorithms free of HTTP concerns.

## D21. Application starts while MySQL is unreachable (refines D5)
- **Problem:** with Flyway at startup and Hibernate validating the schema, an unreachable database prevented startup entirely, so neither "dev fallback" nor "prod not-ready" could exist.
- **Decision:** `DeferredFlywayMigration` defers the migration on *connection* failures only. Hibernate boots without database access (database product/version declared, no JDBC metadata, `ddl-auto=none`). The policy loader runs the deferred migration before each load.
- **Trade-off:** production no longer runs Hibernate's schema validation at boot. Flyway's `validate-on-migrate` remains, and every integration test runs `ddl-auto=validate` against real MySQL.

## D22. Whole-set policy snapshot instead of a per-key cache
- Policies are few, so each instance compiles all enabled policies (pre-parsed `PathPattern`s, validated `RateLimitRule`s) into an immutable, pre-sorted list behind an `AtomicReference`. Request-time resolution is a lock-free linear scan. A per-key cache (e.g. Caffeine) would add eviction and partial-staleness problems for no gain at this size.
- A row that fails to compile is skipped and logged rather than failing the reload (reported as `skippedInvalidPolicies` in health).

## D23. Invalidation messages carry no data
- Pub/Sub messages are `<policyId>:<op>:<instanceId>` and trigger a full reload from MySQL, so MySQL stays the single source of truth. Duplicate, reordered or lost messages are harmless: a lost one is covered by the 30 s periodic reload. Publishing happens **after commit** (`@TransactionalEventListener`), so no instance can load a change that later rolls back.

## D24. Most-specific-match resolution, one policy per request
- Precedence: tier-specific > path specificity > method-specific > priority > lower id. Deterministic and unit-tested as a truth table. Stacking several policies on one request remains an advanced feature.

## D25. JWT claims: never more privilege than stated
- A missing, unknown or `ANONYMOUS` `tier` claim maps to FREE. An invalid token is a 401 everywhere, never a silent downgrade to anonymous. `iss` is validated in addition to signature and expiry.

## D26. Development endpoints guarded twice
- `ratelimiter.dev-endpoints.enabled` (true only in `application-dev.yml`) **and** `@Profile("!prod")`. A misconfigured property alone cannot expose token minting in production (tested).

## D27. Trusted proxies via Tomcat RemoteIpValve, default none
- `server.forward-headers-strategy=native` with `internal-proxies=${TRUSTED_PROXIES:}`. Empty means `X-Forwarded-For` is ignored. Tomcat's own default would trust all private ranges, which inside Docker includes the host gateway, so it is explicitly overridden. No application code parses forwarding headers.

## D28. Rate-limit filter inside the security chain, after authentication, before authorization
- **Alternatives:** a servlet filter after the whole security chain, or a `HandlerInterceptor`. Both run after authorization, so 401/403 responses would be unlimited, letting anyone probe protected endpoints for free. An interceptor also runs after handler mapping, so it can't limit 404s.
- **Trade-off:** requests with an *invalid* token are rejected by `BearerTokenAuthenticationFilter` before the limiter (cheap HMAC check, no Redis call). An IP pre-limit for those is an advanced item.

## D29. 429 and 503 bodies are RFC 7807 documents extended with the contract fields
- `type/title/status/detail/instance` (consistent with every other API error) plus `error/message/retryAfterSeconds` (the documented rate-limit contract). Content type `application/problem+json`.

## D30. Header rounding: always up
- `Retry-After` and `X-RateLimit-Reset` round milliseconds **up** to whole seconds, with `Retry-After` at least 1. A client that obeys the header is never rejected again because of rounding.

## D31. Fail-open responses carry no rate-limit headers
- The quota is unknown during a store failure; emitting stale or invented numbers would mislead clients and dashboards.

## D32. Scripts preloaded before readiness
- Found by `FailureModeIT`: the first call on a cold instance could exceed the 50 ms timeout and fail open. `SCRIPT LOAD` in an `ApplicationRunner` (runs before readiness becomes ACCEPTING_TRAFFIC) warms the connection and the script cache. Not fatal if Redis is down at startup.

## D33. Readiness = initialised + usable policies (R1; supersedes the Phase 1 readiness group)
- **Problem:** with `db` and `redis` in readiness, a Redis or MySQL outage made **every** instance not ready, so a load balancer would remove all of them: a total outage regardless of fail-open.
- **Decision:** readiness = `readinessState, policies`. New group `dependencies` = `db, redis, rateLimitStore` for monitoring. Phase 3's Compose and Nginx health checks must use readiness.

## D34. Circuit breaker at the script-executor boundary with Resilience4j 2.4.0 (D1)
- A decorator around `RateLimitScriptExecutor`. Algorithms, scripts and the filter are unchanged. Refused calls throw a subclass of `RateLimitStoreException`, so the existing failure modes apply. One breaker per instance; no retries.

## D35. Strict half-open: 3 of 3 probes, failed probe reopens first (A1)
- Resilience4j re-applies the 50 % threshold to probes, so 2/3 successes would close. The decorator forces OPEN before reporting a failed probe, so the library never sees a completed cycle containing a failure.

## D36. Probe timeout is per in-flight probe, not per cycle (R5 option B)
- The library's cycle-wide half-open timeout would reopen a low-traffic instance forever (fewer than 3 requests per 2 s). The library's timeout is disabled; the decorator reopens only when one admitted probe stays unresolved for more than 2 s. Results older than 2 s are never recorded.

## D37. Only infrastructure failures count (A4)
- Allow-list classification (`RedisFailureClassifier`): timeouts, connection failures, pool exhaustion, and Redis "cannot serve" replies (OOM, BUSY, LOADING, MASTERDOWN, READONLY, CLUSTERDOWN, TRYAGAIN). Everything else, including Lua runtime errors and malformed replies, is ignored by the breaker.

## D38. Telemetry hook in the filter: observation only (P1)
- `RateLimitFilter` records each decision (result + duration) through `RateLimitTelemetry`. The old constructor is kept and records nothing, so every pre-existing filter test runs unchanged. `RateLimitFilterTelemetryTest` asserts identical responses with and without telemetry.

## D39. Lua scripts held as strings, read once with an explicit class loader
- **Found in 3A:** resource-backed `DefaultRedisScript`s captured the *thread context class loader* of whichever thread first touched them (in tests, a web-app loader that was later stopped, breaking other contexts), and re-checked the file's `lastModified` inside a `synchronized` block on **every** call. Scripts are now loaded once by `ScriptText` and built from strings. The Lua source and the EVALSHA/NOSCRIPT behaviour are unchanged.

## D40. `/stats`: per-instance shards, exactly-once batches
- Hot path: `LongAdder` per (policy, outcome), monotonic, never reset. The flusher batches "sum − watermark" and moves the watermark when the batch is built. A failed batch is retried **unchanged** (same boot id and sequence), and `stats_apply.lua` applies a (boot, seq) at most once per shard. This matters because a timed-out flush can still run later (a paused Redis executes buffered commands), and a naive retry would double-count.
- Keys `rl:stats:{<policyId>}:<instanceId>` and `rl:stats:{<policyId>}:instances` share a hash tag (single Cluster slot). TTL is 24 h after the last flush. Flushes never go through the rate-limit circuit breaker.

## D41. Stats flush uses plain `EVAL`
- It keeps `EVALSHA` exclusive to rate-limit decisions, so Redis's own `INFO commandstats` stays an independent count of decisions that reached Redis (used by `CircuitBreakerIT` and Phase 3B reconciliation). The cost is negligible: one call per policy per 5 s per instance.

## D42. Redis command timeout: 250 ms in the Compose deployment; 50 ms application default (D14 kept)
- **Evidence:** with 50 ms, 1,000 simultaneous requests through Nginx on 1.5-CPU instances produced `RedisCommandTimeoutException: Command timed out after 50 millisecond(s)` → fail-open → open breakers, in 4 of 4 rounds (425–909 fail-open responses per round). Redis's own `EVALSHA` latency was p50 12 µs, **p99 ≈ 61 µs**, p99.9 117 µs, with an empty slow log: the time was spent on the client side, in reply processing under CPU contention. Capping Tomcat at 32 threads did not help (285–841 fail-open). With 250 ms and with 500 ms: exactly 100 accepted in 4 of 4 rounds, 0 fail-open, no breaker opening.
- **Decision (approved):** `REDIS_COMMAND_TIMEOUT=${STACK_REDIS_COMMAND_TIMEOUT:-250ms}` for all three instances in `docker-compose.yml`. The application default stays 50 ms. The value stays configurable; nothing is hardcoded in Java.
- **Scope:** 250 ms is **validated for this deployment** (this laptop, these CPU limits). It is not established as the universal production optimum; Phase 3B measures latency under load and may refine it.
- **Trade-off:** in a real Redis outage, the requests before the breaker opens (about 10 per instance) can each wait up to 250 ms instead of 50 ms; after that the breaker answers in microseconds.

## D43. Benchmark method details (Phase 3B, decisions O1–O5)
- **O1:** report tables only during the campaign (no new Python dependency); charts for the README are generated later
  as dependency-free SVG from the saved results.
- **O2:** request counts are reconciled **exactly over the whole k6 run** (warm-up + measurement) from raw
  per-instance counters read while the instances are idle; latency and resource queries cover only the 180 s
  measurement window. Prometheus cannot split counts exactly at a warm-up/measurement boundary that is not
  scrape-aligned.
- **O3/O4:** `benchmarks/results` is mounted into k6 at run time (no Compose change); warm-up and measurement requests
  carry distinct `User-Agent` markers (`k6/… phase=… run=…`), so the Nginx log is split by phase and run.
- **O5:** one benchmark policy, `bench-profile`, 120 requests per 60 s per user for every algorithm (token bucket
  capacity 120, refill 120 per 60 s). With a fixed allowance, the throttling ratio grows with the arrival rate
  (documented per level in `11-performance-benchmarks.md`).

## D44. Destructive Redis commands only against the verified Compose Redis
- `FLUSHDB`, `CONFIG RESETSTAT` and `SLOWLOG RESET` are on an allow-list and run only after the Compose project,
  service, image, working directory, Redis configuration fingerprint and key prefixes are verified, and only against
  that container id (re-checked before each command). The check refused a stack created from another directory.

## D45. Benchmark-policy conflict guard is a superset matcher
- `setup-policies.sh` rejects every enabled FREE-tier policy that could match the benchmark endpoint, except the `/api/**`
  seed. Pattern matching is a deliberate superset of Spring's `PathPattern` (`**` anywhere, `{var}` possibly empty);
  `{var:regex}` is evaluated only within a subset with identical Java and Python semantics (verified by a 4,545-case
  differential test), and anything uncertain counts as a conflict.

## D46. Load-generator settings (tooling only)
- k6 keeps every latency sample in memory; at 7,500 RPS it reached its 1 GiB limit and collapsed (3,094 RPS achieved).
  Fix: `GOMEMLIMIT=850MiB` and pre-allocated VUs sized for a 200 ms latency tail (200–1,000). The approved k6 container
  limits (2 CPU / 1 GiB) and every validity rule are unchanged.

## D47. Throughput levels reported
- 10,000 RPS: attempted for every algorithm (11 runs, all invalid); not retried. **7,500 RPS** is the highest validated
  level and the maximum sustainable throughput on this laptop. The B2 knee (11,000 RPS) is load-generator bound and is
  not a sustainable-throughput claim; the constant-rate confirmation at 9,000 RPS produced no valid run. B9's
  "at the knee" comparison ran at 7,500 RPS.

## D48. No optimisation of the system under test
- Every candidate from the plan was evaluated against the measurements and rejected: Redis pooling (pool-16 slightly
  worse than the shared connection), GC flags (pauses do not explain the stalls), Tomcat threads (≤ 24 of 200 busy),
  Nginx (≈ 50 % CPU), Redis io-threads (≈ 0.2 core). The shared Lettuce connection stays the default.

## D49. Failure campaigns: F5 without `DEBUG`; one run per fault
- Redis `DEBUG` stays disabled; F5 slows Redis with a calibrated pure-CPU Lua loop sent with `EVAL` (Redis 7 freezes
  `TIME` inside scripts, so the loop length is calibrated from `INFO commandstats`). F1–F5 deliver timelines; retries
  of failure runs were skipped by decision (F1/F2 ran 3–4 times before that).

## D50. Cold-start fail-open left open; evidence storage
- Shortly after an instance restart, a 1,000-request burst can exceed the 250 ms Redis timeout (CPU-quota throttling
  during JIT compilation; `13-failure-handling.md`). Not fixed in Phase 3B: the timeout (D42) and failure semantics
  are unchanged; options are listed for a separate, approved change.
- Benchmark evidence: compact per-run artifacts are committed; bulky raw samples stay local and are covered by
  `benchmarks/results/MANIFEST.sha256`. Expired test JWTs in 73 evidence files were redacted (originals archived
  locally), and the tooling no longer records tokens.

## D51. Phase 4 security remediation (approved 2026-11-12)
- **SEC-1 (local part):** Nginx is published on `127.0.0.1` only. The local demo has no TLS, so it must not be
  reachable from the network; TLS termination remains a deployment requirement (`16-security-review.md`).
- **SEC-3:** policy patterns may not contain `{var:regex}`. Supported syntax is unchanged (`?`, `*`, `**`, `{var}`,
  `{*var}`); no seeded, fallback or documented policy used a regex variable. Enforced in
  `ResolvedPolicy.parsePattern`, so it covers the API (400), rows loaded from MySQL (skipped with an error log) and
  fallback configuration.
- **SEC-6:** Nginx no longer returns `X-Upstream`. The smoke test and benchmark tooling identify the serving instance
  from the Nginx access log (`upstream=`), matching their probes by a unique `User-Agent`. The load-distribution and
  per-instance propagation checks are unchanged in what they verify.
- Benchmark results from Phase 3B were produced with the earlier configuration (header present, port on all
  interfaces); neither change is on the rate-limiting path, and the evidence is left as recorded.
