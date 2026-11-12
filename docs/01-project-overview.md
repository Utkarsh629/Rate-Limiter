# 01 — Project Overview

> Status: **current (Phases 1–3 complete).** All features below are implemented and verified; evidence is in docs 09–13 and `PROJECT_PROGRESS.md`. Phase 4 (documentation, audit, release) is in progress.

## Problem statement

An API served by several identical instances behind a load balancer needs per-client request quotas
(for example 100 requests per minute). If each instance counts on its own, a client spreading
traffic across N instances receives up to N × the quota. The count must live in one place that every
instance reads and updates **atomically**, and checking it must add only a few milliseconds.

## Objectives

1. Enforce one shared quota per client across any number of application instances.
2. Provide three interchangeable algorithms: Token Bucket, Fixed Window Counter, Sliding Window Counter.
3. Manage policies (per IP, user, endpoint, subscription tier) at runtime through a REST API.
4. Behave predictably when Redis or MySQL fails (configurable fail-open / fail-closed).
5. Prove correctness with concurrency and multi-instance tests, and measure performance with real benchmarks.
6. Be observable (Prometheus metrics, Grafana dashboard) and runnable with one `docker compose up`.

## Scope

**In scope:** single-region deployment, one Redis primary, three app instances behind Nginx, MySQL
policy store, JWT-based identity with a development-only token endpoint.

**Out of scope:** Kubernetes, multi-region quota sharing, a user/account system, a UI.

## Features and where they were built

The project runs in four phases (Phase 3 has two parts, 3A and 3B).

| Feature | Phase | Status |
|---|---|---|
| Project skeleton, Redis + MySQL infrastructure, Flyway schema, profiles | 1 Foundation & Architecture | ✅ |
| Three algorithms as atomic Redis Lua scripts (Strategy pattern) | 2 Core Backend & Distributed Logic | ✅ |
| Policy CRUD API, in-memory snapshot cache, cross-instance invalidation (Pub/Sub + periodic reload) | 2 | ✅ |
| Servlet filter, JWT identity, trusted-proxy client IP, `X-RateLimit-*` headers, 429 responses | 2 | ✅ |
| Redis timeouts, circuit breaker, fail-open / fail-closed (503) | 2 | ✅ |
| Micrometer metrics, per-instance statistics shards, `/stats` endpoint | 3A Observability & Deployment | ✅ |
| Full Docker Compose stack: Nginx, 3 instances, Prometheus, Grafana, smoke test | 3A | ✅ |
| k6 benchmark tooling, B0–B10 benchmarks and F1–F5 failure campaigns with recorded results | 3B Performance & Optimization | ✅ |
| Documentation, security review, release | 4 Documentation, Audit & Release | 🔄 |

## Results at a glance

Measured on one Apple M4 laptop running Docker Desktop, with all services and the load generator on the same
machine; relative results, not production capacity. Details and evidence: `11-performance-benchmarks.md`.

- **7,500 RPS** is the highest validated load level and the maximum sustainable throughput for all three algorithms
  (3 valid runs each). 10,000 RPS was attempted and not achieved. The stress-ramp knee of 11,000 RPS was set by the
  load generator and is **not** a sustainable-throughput claim.
- 212 benchmark runs: **153 valid**, **59 invalid** (kept with their reasons, never used in results).
- Zero over-admission in every correctness run; exactly 100 of 1,000 simultaneous requests admitted across three
  instances.
- Known limitation: shortly after an instance restart, a 1,000-request burst can fail open (`13-failure-handling.md`).

## Technology stack

| Layer | Technology |
|---|---|
| Language / framework | Java 17, Spring Boot 3.5.16 |
| Rate-limit state | Redis 7.4 (Lua scripts, TTLs), Lettuce client |
| Policy store | MySQL 8.4, Spring Data JPA, Flyway |
| Security | Spring Security OAuth2 Resource Server (HS256 JWT) |
| Resilience | Resilience4j circuit breaker |
| Observability | Micrometer, Prometheus, Grafana, redis_exporter |
| Testing | JUnit 5, Mockito, Spring Boot Test, Testcontainers, k6 |
| Infrastructure | Docker, Docker Compose, Nginx |
