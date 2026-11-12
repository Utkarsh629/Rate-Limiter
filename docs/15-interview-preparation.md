# 15 — Interview Preparation

> Status: **written in Phase 4** from the implementation, the decision record (docs/04) and measured results only.
> Every number here is in `11-performance-benchmarks.md`, `13-failure-handling.md` or `10-testing-strategy.md`.
> Measurement context to state whenever you quote a number: **one Apple M4 laptop, Docker Desktop, all services and
> the load generator on the same machine**.

## Two-minute explanation

"I built a distributed rate limiter for Spring Boot services that run as several instances behind a load balancer.
The core problem is that if every instance counts requests on its own, a client gets N times its quota. So the count
lives in Redis, and every decision is one Lua script: Redis runs scripts one at a time, so reading the counter,
deciding and writing happen atomically, even with many instances racing. The script uses Redis's own clock, so the
instances' clocks don't matter.

It supports three algorithms per policy: token bucket, fixed window and a sliding window counter. Policies are
managed through an admin REST API, stored in MySQL, and cached in memory on every instance, with Pub/Sub
invalidation, so the database is never on the request path. Clients are identified by JWT, or by IP if anonymous.

For failures, each policy is fail-open or fail-closed: if Redis is down, fail-open lets requests through without
limits and fail-closed answers 503, never 429, because the client didn't exceed anything. A circuit breaker stops
calling Redis during an outage.

I deployed three instances behind Nginx with Prometheus and Grafana, and benchmarked it with k6. On my laptop it
sustained 7,500 requests per second for all three algorithms with zero over-admission, and the limiter itself added
about 1 ms at P99. I kept 212 benchmark runs, including the 59 that failed my validity rules, and I documented a
cold-start limitation I found rather than hiding it."

## Five-minute technical explanation

1. **Problem and invariant.** One shared quota per client across instances; never admit more than the policy allows.
   The invariant is enforced in one place: a Lua script per decision on a single Redis primary.
2. **Atomicity.** Redis executes one script at a time, so read–decide–write cannot interleave. A negative-control
   test does GET-then-SET from Java and admits 1,000 of 1,000 requests against a limit of 100; the Lua version admits
   exactly 100 (docs/09).
3. **Time.** Scripts call Redis `TIME`, so all instances share one clock. Tests inject a fake clock only through a
   test-only interface.
4. **Algorithms.** Token bucket (capacity + continuous refill, burst-friendly), fixed window (one counter per aligned
   window, allows up to 2× at a boundary), sliding window counter (previous window weighted by overlap, O(1) memory,
   bounded approximation). Integer arithmetic, rejections write nothing, keys expire when equivalent to fresh.
   A differential test runs 27,000 random steps against an independent Java model, and a manual mutation check
   confirmed it catches planted bugs (docs/10).
5. **Policies.** CRUD API with validation and optimistic locking; every instance compiles a sorted snapshot (most
   specific match wins: tier, then path specificity, then method, then priority). Changes propagate by Pub/Sub after
   commit, with a 30 s reload as a safety net.
6. **HTTP contract.** Filter after authentication and before authorization; `X-RateLimit-Limit/Remaining/Reset`;
   429 with `Retry-After`; anonymous clients keyed by IP, trusting `X-Forwarded-For` only from Nginx.
7. **Failure handling.** Per-policy fail-open/closed; Resilience4j breaker at the script-executor boundary, counting
   only infrastructure failures; strict 3-of-3 half-open probes; readiness ignores dependency outages so the load
   balancer keeps routing.
8. **Observability.** Bounded-label metrics, histograms down to 50 µs, per-instance statistics shards flushed in
   exactly-once batches (boot id + sequence), Grafana dashboard.
9. **Benchmarks.** Open-model load (constant arrival rate), 60 s warm-up + 180 s measurement, 3 repetitions, strict
   invalid-run rules, exact count reconciliation across five sources. Result: 7,500 RPS sustainable on the laptop;
   10,000 not achieved; the knee at 11,000 was set by the load generator, not the limiter.
10. **What I learned.** The interesting problems were in measurement and operations: a 50 ms Redis timeout that failed
    open under bursts on CPU-limited containers, a load generator that ran out of memory, and a cold-start effect from
    JIT compilation under a CPU quota.

## Architecture walkthrough (whiteboard order)

Client → Nginx (round robin, keepalive, passive failover) → one of three instances → Tomcat (`RemoteIpValve`, trusted
proxy) → Spring Security (HS256 JWT: signature, expiry, issuer) → `RateLimitFilter`: identity → policy snapshot →
algorithm → `CircuitBreakingScriptExecutor` → Redis `EVALSHA` → headers + 429/503 or continue → telemetry (metrics +
statistics shard) → controller. Side channels: MySQL (policy CRUD, snapshot reload), Redis Pub/Sub (invalidation),
Prometheus scraping each instance, Grafana.

## 30 questions with answers

1. **Why Redis and not a database or local counters?** One authoritative, fast, in-memory store every instance can
   update atomically; local counters give N× the quota; a SQL row lock per request would be far slower.
2. **Why a Lua script instead of `INCR` + `EXPIRE` or a transaction?** The decision needs read, compute and
   conditional write; `MULTI/EXEC` cannot branch on values it reads; `WATCH` retries under contention; a script is one
   atomic step.
3. **How do you prove atomicity?** Concurrency tests: 1,000 simultaneous acquisitions with limit 100 → exactly 100,
   repeated, from one and from three Redis clients; plus a negative control that over-admits without Lua. Under load:
   B6 admitted exactly 100 of 1,000 simultaneous requests across three instances in all 6 runs.
4. **Why Redis `TIME` inside the script?** One clock for all instances; no skew between app servers.
5. **Token bucket vs fixed window vs sliding window?** Bucket: smooth rate with bursts up to capacity. Fixed window:
   simplest, but a client can send 2× the limit around a boundary. Sliding window counter: smooths the boundary with a
   weighted previous window, O(1) memory, approximate.
6. **Why the sliding window counter and not the sliding log?** The log stores one entry per request (memory grows with
   the limit); the counter stores two numbers. Measured key size: 112 bytes.
7. **How accurate is the sliding window counter?** The current window's count never exceeds the limit; across an
   arbitrary 60 s span it can admit up to about 2× in the worst case, spread out. B10 showed zero over-admission against
   the bound used for verification (limit × windows touched).
8. **What happens when Redis is down?** Each policy decides: fail-open (200 without rate-limit headers) or fail-closed
   (503 with `Retry-After`). Never 429, because the client didn't exceed a limit. Measured in F1: no 429 during a 20 s
   outage.
9. **Why a circuit breaker?** Without it every request waits for the timeout. With it, about 10 requests per instance
   pay the timeout, then decisions take microseconds until a 5 s wait and 3 successful probes.
10. **Why is readiness independent of Redis and MySQL?** If readiness failed, the load balancer would remove every
    instance at once during a Redis outage. The failure mode already defines the behaviour; dependency health is
    reported separately.
11. **How do policy changes reach all instances?** After the database commit, a data-free Pub/Sub message tells every
    instance to reload its snapshot; a periodic 30 s reload covers lost messages.
12. **Why not read the policy from MySQL per request?** Latency and a hard dependency on the hot path; the snapshot is
    an immutable, pre-sorted list.
13. **How is a policy chosen when several match?** Tier-specific beats any-tier, then the more specific path, then a
    specific method, then priority, then lower id. One policy per request.
14. **How do you identify the client?** JWT subject for authenticated users; client IP for anonymous ones. Tier comes
    from the token and is never escalated.
15. **How do you stop `X-Forwarded-For` spoofing?** Tomcat trusts the header only from the configured proxy address
    (Nginx); the smoke test sends a spoofed header and checks it is ignored.
16. **Why 503 for fail-closed and not 429?** 429 means the client exceeded its quota; 503 means the service cannot
    decide right now. Clients and dashboards must be able to tell them apart.
17. **How do `/stats` counts stay exact across instances?** Each instance keeps monotonic counters and flushes the
    delta as a batch with (boot id, sequence); a Lua script applies each batch at most once, so retries after
    ambiguous timeouts never double count.
18. **Why per-instance statistics shards?** A single counter key incremented by all traffic would be a hot key; shards
    are summed on read.
19. **What metrics did you expose and how did you avoid cardinality problems?** Decision counts and latency histograms
    labelled by algorithm and result only: never user, IP or path.
20. **How did you measure the limiter's overhead separately from HTTP?** An in-process timer around the decision, plus
    an A/B run with the limiter disabled: +0.13 ms at P50 and +2.3 ms at P99 end to end at 5,000 RPS.
21. **What is the maximum throughput?** 7,500 RPS validated for all three algorithms on my laptop (3 valid runs each,
    every criterion met). 10,000 was not achieved. I don't quote the 11,000 knee as capacity, because the load
    generator's CPU set it.
22. **What made a benchmark run invalid?** Any dropped request in the load generator, achieved rate below 99 %,
    load-generator CPU above 85 %, non-429 errors above 0.1 %, counts not reconciling, incomplete scrapes, or no Redis
    reset. 59 of 212 runs were invalid and are kept with reasons.
23. **Why an open-model load generator?** A closed model slows down when the server does, hiding latency
    (coordinated omission); constant arrival rate keeps sending.
24. **How do you know the counts in a run are right?** k6, the Nginx access log, the instances' own counters,
    Prometheus and `/stats` must agree exactly, plus Redis's own `EVALSHA` count.
25. **What was the bottleneck?** At 7,500 RPS nothing was saturated; the limiting factors were short latency stalls on
    the shared laptop and, above about 12,000 RPS, the load generator's CPU. So I applied no optimisation: every
    candidate (pooling, GC flags, thread pools, Nginx, Redis I/O threads) was rejected on evidence.
26. **Did connection pooling help?** No. A 16-connection pool was slightly worse than Lettuce's single multiplexed
    connection (limiter P99 0.95 vs 0.76 ms at 5,000 RPS), so the default stayed.
27. **What happens with a hot key?** One Redis key on one thread; measured at 2,000 and 5,000 RPS from one user, all
    valid. Mitigation if needed: a local negative cache until `Retry-After`.
28. **What happens if an instance dies?** Nginx's passive health check skips it; quota state lives in Redis, so
    nothing is lost. Measured in F3: no client-visible errors while it was down.
29. **What would you change for production?** TLS, Redis authentication and replication or Sentinel, an edge limit for
    unauthenticated traffic, asymmetric JWT keys with audience checks, and a fix for the cold-start effect (below).
30. **What is the biggest weakness you found?** Cold start: right after a restart, a 1,000-request burst on a
    1.5-CPU instance triggers JIT compilation, the CPU quota throttles the JVM, the Redis client's I/O thread stalls,
    and calls cross the 250 ms timeout, so some requests fail open. Measured, documented and left as an open issue
    with options rather than masking it by raising the timeout.

## Failure scenarios to describe

| Scenario | Behaviour | Evidence |
|---|---|---|
| Redis paused 20 s | Fail-open 200s, fail-closed 503s, no 429; breakers open in 1–5 s, close 4–6 s after recovery | F1 |
| Redis restart | Brief 503s; recovered in 9–11 s; quotas reset | F2 |
| Instance killed | Nginx failover, no client errors while down | F3 |
| MySQL stopped | Limiting unaffected (snapshot); policy API 503 | F4 |
| Redis slow | Blocks below the timeout absorbed; above it, breakers open | F5 |
| Cold start + burst | Possible fail-open after restart | `13-failure-handling.md` |

## Trade-offs worth discussing

- Atomicity through a single Redis primary vs availability during failover (asynchronous replication can briefly
  over-admit).
- Fail-open (availability) vs fail-closed (strictness), chosen per policy.
- Approximate sliding window (O(1) memory) vs exact sliding log (memory per request).
- Policy staleness (up to 30 s if a message is lost) vs no database on the hot path.
- 250 ms Redis timeout in the deployment: fewer false fail-opens under CPU pressure vs slower failure detection
  before the breaker opens.
- Laptop benchmarks: reproducible and honest about limits, but not production capacity.

## Problems found and fixed during the project (good stories)

- **Timeout too tight for the deployment:** 50 ms Redis timeouts failed open under 1,000-request bursts on
  CPU-limited containers, although Redis answered in about 61 µs. Diagnosed, validated 250 ms for the Compose
  deployment, kept 50 ms as the application default (D42).
- **Stale class loader in Lua script objects:** resource-backed scripts captured a stopped web-app class loader in
  tests; fixed by reading scripts once as strings (D39).
- **Load generator out of memory:** k6 kept every latency sample and collapsed at 7,500 RPS (3,094 RPS achieved);
  fixed with a Go memory limit and VUs sized for the latency tail, without changing any validity rule (D46).
- **Redis freezes time in scripts:** a slow-down script that looped on `TIME` never ended; replaced by a calibrated
  CPU loop (D49).
- **Policy precedence surprise:** an existing policy whose pattern also matches the benchmark endpoint could silently
  take precedence; the setup guard became a conservative superset matcher that treats anything uncertain as a
  conflict (D45).

## Resume material (measured metrics only)

State "on a laptop benchmark" or "local Docker Compose benchmark" next to any throughput number.

**ATS-friendly (keywords):**
- Designed and built a distributed rate limiter in Java 17 and Spring Boot 3 using Redis Lua scripts for atomic,
  cluster-wide enforcement; implemented Token Bucket, Fixed Window and Sliding Window algorithms.
- Implemented JWT-based identity (Spring Security), runtime policy management (REST API, MySQL, Flyway) with Redis
  Pub/Sub cache invalidation, and Resilience4j circuit breaking with fail-open and fail-closed modes.
- Deployed three instances behind Nginx with Docker Compose, Micrometer, Prometheus and Grafana; wrote 318 automated
  tests (JUnit 5, Testcontainers) and a k6 benchmark suite of 212 runs with automated validity checks.
- Sustained 7,500 requests per second with zero over-admission and about 1 ms P99 limiter overhead in a local
  three-instance benchmark.

**Technical (for engineering readers):**
- Built a Redis-backed rate limiter enforcing one quota per client across instances with one Lua script per decision
  (Redis `TIME` as the clock); verified exactly 100 of 1,000 simultaneous requests admitted across three instances.
- Added per-policy fail-open/fail-closed and a per-instance circuit breaker; under a 20 s Redis outage at 2,000 RPS the
  service returned no 429s and recovered within seconds.
- Built a benchmark harness with exact five-way count reconciliation and strict invalid-run rules; measured 7,500 RPS
  sustainable (laptop), limiter P99 ≤ 1.7 ms, and documented the load generator as the bound at higher rates.

**Short (one line):**
- Distributed rate limiter (Java, Spring Boot, Redis Lua, Docker): atomic multi-instance enforcement, 7,500 RPS with
  zero over-admission on a laptop benchmark, 318 tests.

## Questions to expect about the numbers (and honest answers)

- *"Would it do 7,500 RPS in production?"* — Unknown: everything shared one laptop, including the load generator.
  The result shows relative behaviour; production capacity needs production hardware and a separate load generator.
- *"Why not 10,000?"* — The runs were invalid: the load generator dropped requests and the instances approached their
  CPU limits. I recorded them as invalid instead of reporting a number.
- *"Why are 59 runs invalid?"* — Strict rules: a single dropped request invalidates a run; tooling bugs and laptop sleep
  are recorded too. The invalid runs and their reasons are part of the evidence.
