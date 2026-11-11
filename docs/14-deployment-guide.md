# 14 — Deployment Guide

> Status: **Phase 1 (infrastructure + single local instance).** Phase 7 adds Nginx, three app instances, Prometheus and Grafana.

## Prerequisites

| Tool | Version used | Check |
|---|---|---|
| JDK | 17 | `java -version` |
| Docker + Compose v2 | 28.x / 2.40 | `docker compose version` |
| Maven | not required; the project ships `./mvnw` (Maven 3.9.11) | `./mvnw -v` |

## 1. Configure secrets

```bash
cp .env.example .env
```

Edit `.env` and replace every `change-me`. Random values:

```bash
openssl rand -base64 24
```

`.env` is git-ignored. Docker Compose reads it automatically. In the `dev` profile the application
also reads it (`spring.config.import: optional:file:.env[.properties]`), so both share one set of credentials.

| Variable | Used by | Purpose |
|---|---|---|
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD` | Compose, app | Policy database (Compose refuses to start if any is missing) |
| `MYSQL_HOST_PORT` | Compose, app (dev) | Host port for MySQL; `3307` avoids clashing with a local MySQL |
| `REDIS_HOST_PORT` | Compose, app (dev) | Host port for Redis |
| `INSTANCE_ID` | app | Unique per instance; names its statistics shard |
| `JWT_SECRET` | app | **Required.** Base64 HMAC-SHA256 key, ≥ 32 bytes (`openssl rand -base64 48`); startup fails without it |
| `TRUSTED_PROXIES` | app | Regex of proxy IPs whose `X-Forwarded-For` is trusted; empty = none (default) |
| `REDIS_COMMAND_TIMEOUT`, `REDIS_POOL_ENABLED`, … | app | Optional tuning (see `.env.example`) |

Rate-limiting behaviour is configured with `ratelimiter.filter.*` and `ratelimiter.default-failure-mode` (see `07-api-documentation.md`).

## 2. Start infrastructure

```bash
docker compose up -d --wait
```

```bash
docker compose ps
```

Both `redis` and `mysql` should show `(healthy)`. Ports are published on `127.0.0.1` only.

## 3. Build and test

```bash
./mvnw verify
```

Runs unit tests (Surefire, `*Test`) and integration tests (Failsafe, `*IT`, Testcontainers). Integration
tests start their own Redis and MySQL containers and do not use the Compose services.

Unit tests only (no Docker needed):

```bash
./mvnw test
```

## 4. Run the application

```bash
./mvnw spring-boot:run
```

(`spring-boot:run` activates the `dev` profile.) Or run the packaged jar:

```bash
./mvnw -DskipTests package && java -jar target/distributed-rate-limiter-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

## 5. Health checks

```bash
curl -s localhost:8080/actuator/health/readiness
```

```bash
curl -s localhost:8080/actuator/health/liveness
```

```bash
curl -s localhost:8080/actuator/flyway
```

| Probe | Includes | DOWN means |
|---|---|---|
| liveness | `livenessState` | The JVM is broken; restart it |
| readiness | `readinessState`, `policies` | Not initialised or no usable policies: don't route traffic here |
| dependencies | `db`, `redis`, `rateLimitStore` (circuit breaker) | A dependency is down or the breaker is open (monitoring only) |

Since 2D (decision R1), a Redis or MySQL outage does **not** make an instance unready. Failure modes,
the circuit breaker and the cached policy snapshot keep it serving, and the outage shows in
`/actuator/health/dependencies`. Load balancers and container health checks must use **readiness**,
not `/actuator/health`.

```bash
curl -s localhost:8080/actuator/health/dependencies
```

The `flyway` endpoint is exposed only in the `dev` profile.

## 5b. Try the policy API (dev profile)

```bash
ADMIN=$(curl -s -X POST localhost:8080/api/v1/auth/demo-token -H 'Content-Type: application/json' -d '{"userId":"admin-1","tier":"ENTERPRISE","roles":["ADMIN"]}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
```

```bash
curl -s localhost:8080/api/v1/policies -H "Authorization: Bearer $ADMIN"
```

```bash
curl -s localhost:8080/api/v1/demo/whoami
```

### Watch rate limiting work

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/demo-token -H 'Content-Type: application/json' -d '{"userId":"alice","tier":"FREE"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
```

```bash
for i in $(seq 22); do curl -s -o /dev/null -w "%{http_code} remaining=%header{x-ratelimit-remaining} retry-after=%header{retry-after}\n" localhost:8080/api/v1/demo/profile -H "Authorization: Bearer $TOKEN"; done
```

Expected: 20 × `200` with `remaining` counting down from 19, then `429` with `retry-after=3`.

Anonymous, limited per IP (10 per minute):

```bash
for i in $(seq 12); do curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/v1/demo/public; done
```

Fail-closed vs fail-open (pause Redis, then unpause):

```bash
docker compose pause redis
```

```bash
curl -s -i localhost:8080/api/v1/demo/public
```

```bash
docker compose unpause redis
```

`/public` keeps answering 200 (fail-open, no rate-limit headers); `/enterprise` with an ENTERPRISE
token answers 503 (its policy is fail-closed). After about 10 failed requests the circuit breaker
opens: `/actuator/health/dependencies` shows `"circuitBreaker":"OPEN"` and responses come back in about
1–3 ms instead of about 58 ms. About 5 s after unpausing, three requests probe Redis and limiting resumes.

Redis connection pooling (`REDIS_POOL_ENABLED=true`) now gives each script call a pooled connection
(the shared connection is switched off). It is off by default and is benchmarked in Phase 3.

To reset all quotas locally:

```bash
docker compose exec redis redis-cli FLUSHDB
```

See `07-api-documentation.md` for every endpoint.

## 5c. Full stack: Nginx + 3 instances + monitoring (Phase 3A)

```bash
scripts/stack-up.sh
```

This builds `ratelimiter-app:local` and starts all nine services, waiting until each is healthy. Add `GRAFANA_ADMIN_PASSWORD` (and optionally `NGINX_HOST_PORT`) to `.env` first; see `.env.example`.

| URL | What |
|---|---|
| `http://localhost:8080` | API through Nginx (the only API entry point; `/actuator/**` is blocked) |
| `http://localhost:3000` | Grafana (user `admin`, password from `.env`), dashboard "Distributed Rate Limiter" |
| `http://localhost:9090` | Prometheus |

The prod profile has no demo-token endpoint. Mint tokens locally from `JWT_SECRET`:

```bash
ADMIN=$(python3 scripts/bench/mint-tokens.py --print-admin)
```

```bash
curl -s localhost:8080/api/v1/policies/2/stats -H "Authorization: Bearer $ADMIN"
```

Deployment settings (environment, `docker-compose.yml`):

| Variable | Default in the stack | Meaning |
|---|---|---|
| `STACK_REDIS_COMMAND_TIMEOUT` | **250ms** | Redis command timeout for all three instances (D42). The application default is 50 ms |
| `TOMCAT_MAX_THREADS` | 200 | Tomcat worker threads per instance |
| `REDIS_POOL_ENABLED` | false | Lettuce pooling (pooling compared in 3B) |
| `NGINX_HOST_PORT` | 8080 | Host port of Nginx |

Infrastructure only (for `./mvnw spring-boot:run` on port 8080, which then conflicts with Nginx if the full stack is up):

```bash
docker compose up -d --wait redis mysql
```

### Smoke test

```bash
scripts/stack-smoke.sh --clean
```

`--clean` deletes this project's containers and volumes first (policies are re-seeded by Flyway). Without it, the smoke test runs against the current stack. It takes about 2.5 minutes; the report is written to `benchmarks/smoke/<run-id>/report.json`.

## 6. Inspect the data stores

```bash
docker compose exec redis redis-cli INFO server
```

```bash
docker compose exec mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SELECT id,name,tier,algorithm,limit_value FROM rate_limit_policy"'
```

## 7. Shut down

Stop the app with Ctrl+C. It shuts down gracefully, waiting up to 20 s for in-flight requests.

```bash
docker compose down
```

Also delete the MySQL volume (all policies):

```bash
docker compose down -v
```

## Profiles

| Profile | Use | Differences |
|---|---|---|
| (none) | base | Secure defaults: MySQL TLS required, health details hidden, no fallback policies |
| `dev` | local development | Reads `.env`, MySQL TLS off (local container only), `flyway` endpoint exposed, fallback policies allowed, demo token + whoami endpoints, Swagger UI at `/swagger-ui.html` |
| `prod` | Compose stack (Phase 7) | Restates fallback disabled and hidden health details explicitly |
| `test` | integration tests | Connection details injected by Testcontainers |
