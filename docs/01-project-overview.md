# 01 — Project Overview

> Status: **current as of Phase 1.** Feature list reflects the approved design; see `PROJECT_PROGRESS.md` for what is implemented and verified.

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

## Features (approved design)

| Feature | Phase |
|---|---|
| Project skeleton, Redis + MySQL infrastructure, Flyway schema | 1 |
| Three algorithms as atomic Redis Lua scripts (Strategy pattern) | 2 |
| Policy CRUD API, in-memory snapshot cache, cross-instance invalidation | 3 |
| Servlet filter, JWT identity, trusted-proxy client IP, `X-RateLimit-*` headers, 429 responses | 4 |
| Redis timeouts, circuit breaker, fail-open / fail-closed (503) | 5 |
| Micrometer metrics, per-instance statistics shards, `/stats` endpoint | 6 |
| Full Docker Compose stack: Nginx, 3 instances, Prometheus, Grafana | 7 |
| k6 benchmarks with recorded results | 8 |

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
