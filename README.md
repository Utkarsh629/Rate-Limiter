# Distributed Rate Limiter

A rate limiter for horizontally scaled Spring Boot services. Every instance shares one Redis-backed
quota per client, enforced atomically with Lua scripts. Policies are managed at runtime and stored in MySQL.

> **Status: Phase 3A (Observability & Deployment) implemented, awaiting review.** Phases 1–2 are complete: three algorithms, policies, JWT identity, the HTTP filter and a Redis circuit breaker, verified across three instances. Full Docker Compose stack (Nginx, 3 instances, Prometheus, Grafana) with an automated smoke test. Performance benchmarks (3B) have not been run yet.
> See [PROJECT_PROGRESS.md](PROJECT_PROGRESS.md). This README is completed in the final phase with measured results only.

## Stack

Java 17 · Spring Boot 3.5.16 · Spring Security (JWT) · Redis 7.4 (Lua, Pub/Sub) · MySQL 8.4 · Flyway · springdoc-openapi · Testcontainers · Docker Compose
(planned: Resilience4j, Micrometer, Prometheus, Grafana, Nginx, k6)

## Quick start (current phase)

```bash
cp .env.example .env
```

Replace the `change-me` values in `.env`, then:

```bash
docker compose up -d --wait
```

```bash
./mvnw verify
```

```bash
./mvnw spring-boot:run
```

```bash
curl -s localhost:8080/actuator/health/readiness
```

Full instructions: [docs/14-deployment-guide.md](docs/14-deployment-guide.md).

## Documentation

| | |
|---|---|
| [00 Architecture proposal](docs/00-architecture-proposal.md) | [08 Database schema](docs/08-database-schema.md) |
| [01 Overview](docs/01-project-overview.md) | [09 Concurrency & correctness](docs/09-concurrency-and-distributed-correctness.md) |
| [02 Requirements](docs/02-requirements.md) | [10 Testing strategy](docs/10-testing-strategy.md) |
| [03 Architecture](docs/03-system-architecture.md) | [11 Benchmarks](docs/11-performance-benchmarks.md) |
| [04 Design decisions](docs/04-design-decisions.md) | [12 Observability](docs/12-monitoring-and-observability.md) |
| [05 Algorithms](docs/05-rate-limiting-algorithms.md) | [13 Failure handling](docs/13-failure-handling.md) |
| [06 Redis data design](docs/06-redis-data-design.md) | [14 Deployment guide](docs/14-deployment-guide.md) |
| [07 API](docs/07-api-documentation.md) | [15 Interview preparation](docs/15-interview-preparation.md) |
