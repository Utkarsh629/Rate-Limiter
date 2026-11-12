# 12 — Monitoring and Observability

> Status: **implemented and verified in Phase 3A** (metrics, Prometheus, Grafana, `/stats`); **used for the Phase 3B measurements** (below).

## Endpoints

| Endpoint | Reachable from | Purpose |
|---|---|---|
| `/actuator/prometheus` (each instance) | Compose network only. Nginx returns 404 for `/actuator/**`; app ports are not published | Prometheus scrape (no token: decision P2) |
| `http://localhost:9090` | Host loopback | Prometheus (scrape interval 5 s) |
| `http://localhost:3000` | Host loopback | Grafana (admin password from `.env`) |
| `GET /api/v1/policies/{id}/stats` | Through Nginx, `ROLE_ADMIN` | Per-policy totals across instances (see below) |
| `/actuator/health/dependencies` | Compose network | `db`, `redis`, `rateLimitStore` (circuit breaker) |

## Meter catalogue

| Prometheus name | Type | Tags | What it measures | Recorded in |
|---|---|---|---|---|
| `ratelimiter_requests_total` | counter | `algorithm`, `result` ∈ allowed / rejected / fail_open / fail_closed | One per rate-limit decision | `RateLimitFilter` → `RateLimitTelemetry` |
| `ratelimiter_allowed_total` | counter | `algorithm` | Allowed decisions (fail-open is **not** counted here) | same |
| `ratelimiter_rejected_total` | counter | `algorithm` | 429 decisions | same |
| `ratelimiter_request_duration_seconds` | histogram | `algorithm`, `result` | **Rate-limiter overhead**: identity + policy lookup + algorithm + Redis round trip + result mapping | same |
| `ratelimiter_redis_duration_seconds` | histogram | `algorithm`, `outcome` ∈ success / error | Redis script round trip as the client sees it. Calls refused by the circuit breaker are not timed | `RedisTemplateScriptExecutor` |
| `ratelimiter_algorithm_executions_total` | counter | `algorithm` | Rate-limit scripts sent to Redis | same |
| `ratelimiter_redis_errors_total` | counter | `type` ∈ timeout / connection / pool / server / circuit_open | Infrastructure failures and breaker refusals (2D) | `CircuitBreakingScriptExecutor` |
| `ratelimiter_redis_ignored_errors_total` | counter | — | Non-infrastructure errors (never trip the breaker) | same |
| `ratelimiter_circuit_breaker_transitions_total` | counter | `from`, `to` | Breaker state changes (2D) | breaker event listener |
| `ratelimiter_stats_flushes_total` | counter | `result` ∈ success / failure | `/stats` shard flushes | `PolicyStatsCollector` |
| `resilience4j_circuitbreaker_*` | various | `name`, `state`, `kind` | Resilience4j binding (state, calls, failure rate, not-permitted calls) | binding (2D) |
| `http_server_requests_seconds` | histogram | Spring MVC tags | Server time per request (histogram enabled in 3A) | Spring Boot |
| JVM, process, Tomcat (`tomcat_threads_*`, enabled in 3A), Hikari | | | CPU, heap, GC, threads, DB pool | Spring Boot |
| `redis_*` | | | Redis CPU, memory, ops, per-command calls and time, clients, keys | `redis_exporter` |

**Labels are bounded.** All `ratelimiter_*` series are created at startup for the fixed tag space (3 algorithms × 4 results, etc.). No series is created per user, IP, path or policy. `RateLimitTelemetryTest` asserts that the series count is identical after 1 policy and after 500. `application` is a common tag; `instance` comes from the Prometheus target label.

**Histograms** (`publishPercentileHistogram`, `minimumExpectedValue=50 µs`, `maximumExpectedValue=1 s`): rate-limit decisions are sub-millisecond, and Micrometer's default range would put them in one or two buckets. The first bucket boundary is `le="5.0E-5"` (50 µs). Percentiles across instances are always computed as `histogram_quantile(q, sum by (le) (rate(..._bucket[1m])))`, **never** by averaging per-instance percentiles. The smoke test checks every percentile query for this.

### Actual samples (instance app-1, after the Phase 3A clean-state smoke run, 2026-11-11)
```
ratelimiter_requests_total{algorithm="token_bucket",application="distributed-rate-limiter",result="allowed"} 19290.0
ratelimiter_requests_total{algorithm="token_bucket",application="distributed-rate-limiter",result="rejected"} 2058.0
ratelimiter_allowed_total{algorithm="token_bucket",application="distributed-rate-limiter"} 19290.0
ratelimiter_rejected_total{algorithm="token_bucket",application="distributed-rate-limiter"} 2058.0
ratelimiter_algorithm_executions_total{algorithm="token_bucket",application="distributed-rate-limiter"} 21348.0
ratelimiter_redis_duration_seconds_count{algorithm="token_bucket",application="distributed-rate-limiter",outcome="success"} 21348
ratelimiter_request_duration_seconds_count{algorithm="token_bucket",application="distributed-rate-limiter",result="allowed"} 19290
ratelimiter_request_duration_seconds_bucket{algorithm="token_bucket",application="distributed-rate-limiter",result="allowed",le="5.0E-5"} 0
ratelimiter_stats_flushes_total{application="distributed-rate-limiter",result="success"} 18.0
```
These are counters from a functional smoke run, not benchmark results. Latency values are deliberately not quoted here; they are measured under controlled conditions in Phase 3B.

## Statistics (`/stats`) versus metrics

| | Prometheus metrics | `/api/v1/policies/{id}/stats` |
|---|---|---|
| Granularity | Per algorithm and result, per instance | **Per policy**, per instance and total |
| Storage | Prometheus, scraped every 5 s | Redis shards `rl:stats:{<policyId>}:<instanceId>` (docs/06) |
| Freshness | Scrape interval | Flush interval, **5 s** |
| Loss on instance crash | Samples since the last scrape | **Counts not yet flushed (≤ 5 s)** |
| Exactly once | n/a | Yes: each batch has (boot id, sequence) and is applied at most once, even when a timed-out flush runs later (verified with a paused Redis) |
| Used for enforcement | No | No |

Actual `/stats` response (free-tier policy, after the smoke run; 57,391 load requests + 30 distribution-check requests):
```json
{"policyId":2,"allowed":57421,"rejected":2611,"failOpen":0,"failClosed":0,"rejectionRate":0.0435,
 "instances":[{"instanceId":"app-1","allowed":19147,"rejected":864,"failOpen":0,"failClosed":0,"lastFlushAt":"2026-11-11T03:43:49.976Z"},
              {"instanceId":"app-2","allowed":19149,"rejected":862,"failOpen":0,"failClosed":0,"lastFlushAt":"2026-11-11T03:43:50.251Z"},
              {"instanceId":"app-3","allowed":19125,"rejected":885,"failOpen":0,"failClosed":0,"lastFlushAt":"2026-11-11T03:43:50.024Z"}],
 "consistency":"eventual: each instance flushes every 5 s; unflushed counts of a crashed instance are lost"}
```

## Grafana dashboard

Provisioned automatically (`infra/grafana/provisioning/`, dashboard JSON generated by `scripts/grafana/build_dashboard.py` into `infra/grafana/dashboards/ratelimiter.json`, uid `ratelimiter`). 14 panels; the first 8 are the required ones:

| # | Panel | Main query |
|---|---|---|
| 1 | Incoming requests per second | `sum(rate(ratelimiter_requests_total[1m]))`, `sum by (instance) (...)` |
| 2 | Accepted vs rejected | `sum by (result) (rate(ratelimiter_requests_total[1m]))` |
| 3 | P50/P95/P99 latency | `histogram_quantile(q, sum by (le) (rate(ratelimiter_request_duration_seconds_bucket[1m])))` and the same over `http_server_requests_seconds_bucket{uri!~"/actuator.*"}` |
| 4 | Redis operation latency | Client: quantiles of `ratelimiter_redis_duration_seconds_bucket{outcome="success"}`. Server: `sum(rate(redis_commands_duration_seconds_total{cmd="evalsha"}[1m])) / sum(rate(redis_commands_total{cmd="evalsha"}[1m]))` |
| 5 | Redis failures and circuit breaker | `sum by (type) (rate(ratelimiter_redis_errors_total[1m]))`, breaker state per instance, transitions |
| 6 | Algorithm-wise distribution | `sum by (algorithm) (rate(ratelimiter_algorithm_executions_total[1m]))` |
| 7 | Backend instance health | `up{job="app"}`, `up{job="redis"}`, `redis_up` |
| 8 | CPU and memory | `process_cpu_usage`, heap MiB per instance, Redis memory MiB, Redis CPU |
| 9–14 | Tomcat busy threads, GC pause rate, Redis commands/s and clients, Redis keys, Hikari active connections, stats flushes | |

**Verified (smoke run 20261111-091138):** the dashboard is provisioned (Grafana API 200), the datasource is healthy, and every required panel returned data after the 60 s load. Series per panel: 1 → 4, 2 → 4, 3 → 6, 4 → 4, 5 → 14, and 6, 7, 8 non-empty.

Screenshots are not saved automatically: Grafana's image renderer is not installed in this stack, so panel data is verified through the API. Screenshots for the README are a Phase 4 task.

## How Phase 3B measured with these metrics

`scripts/bench/collect.py` reads the same meters the dashboard shows, but only from saved data (the dashboard was
not used to produce any number). Per run:

| What | Source |
|---|---|
| Limiter overhead P50/P95/P99/P99.9, avg, max | `histogram_quantile(q, sum by (le) (increase(ratelimiter_request_duration_seconds_bucket{algorithm=…}[180s])))` at the end of the measurement window; also split by `result` (allowed/rejected) |
| Redis round trip (client) | the same over `ratelimiter_redis_duration_seconds_bucket` |
| Redis server latency | `INFO latencystats` (EVALSHA percentiles, after `CONFIG RESETSTAT` at the run start), `SLOWLOG` before/after |
| Decision counts | `ratelimiter_requests_total` read from every instance's `/actuator/prometheus` while idle, and from Prometheus at two idle instants after the final scrape (exact, decision O2) |
| JVM / Tomcat | `process_cpu_usage`, heap, GC pauses (`jvm_gc_pause_seconds`), live threads, `tomcat_threads_busy_threads` (range queries, 5 s steps) |
| Redis resources | redis_exporter: CPU (total and main thread), memory, commands/s, clients; `MEMORY USAGE` of sample keys |
| Containers | `docker stats` every second (CPU %, memory) — independent of Prometheus; k6's own CPU for the validity rule |
| Scrape completeness | `count_over_time(up{job="app"}[window])` per instance |
| Breaker transitions (F1–F5) | `resilience4j_circuitbreaker_state` and `ratelimiter_circuit_breaker_transitions_total`, queried at 1 s over 5 s scrapes |

Every query and its raw answer is saved in each run's `prometheus/queries.json`. Measured values:
`11-performance-benchmarks.md`.

Observations from the campaign: the meters' per-request cost is included in the measured limiter overhead (in-process
P99 0.78–1.07 ms at 5,000 RPS); histogram buckets start at 50 µs, so sub-50 µs values are not resolved (Redis server
latency comes from `latencystats` instead); `ratelimiter_redis_duration_seconds_max` is a decaying window maximum and
was used only as an indicator in the cold-start investigation (`13-failure-handling.md`).

## Alert suggestions (not deployed)
| Alert | Expression (sketch) |
|---|---|
| Breaker open | `max by (instance) (resilience4j_circuitbreaker_state{state="open"}) == 1` for 1 m |
| Fail-open traffic | `sum(rate(ratelimiter_requests_total{result="fail_open"}[5m])) > 0` |
| Limiter overhead P99 | `histogram_quantile(0.99, sum by (le) (rate(ratelimiter_request_duration_seconds_bucket[5m]))) > 0.02` |
| Stats flush failing | `increase(ratelimiter_stats_flushes_total{result="failure"}[5m]) > 0` |
| Instance down | `up{job="app"} == 0` |
