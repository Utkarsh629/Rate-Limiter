# 16 — Security Review

> Status: **review completed in Phase 4 (2026-11-12). Approved remediation applied the same day: SEC-1 (local part),
> SEC-3, SEC-6 and the SEC-10 leftover (decision D51).** The remaining findings are documented deployment
> recommendations. Evidence and verification are under each finding and in "Remediation status" below. Scope: the application (`src/main`), the Compose deployment
> (`docker-compose.yml`, `infra/`), the Dockerfile, secrets handling, and the benchmark tooling and evidence.

## Method

Read-only review of the security configuration and request path, checked against the running Compose stack where
possible (response headers, exposed ports), plus a secret scan of every file prepared for git (no `JWT_SECRET` value,
password or `.env` content found). No penetration test and no automated dependency-vulnerability scan were run (no
scanner is installed; see SEC-11).

Severity: **High** (exploitable remotely in the intended deployment, leads to compromise), **Medium** (realistic risk
if deployed beyond a local machine), **Low** (defence in depth, or requires a privileged or local actor), **Info**
(observation or confirmed control). No High finding was identified.

## Findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| SEC-1 | Medium | No TLS; the API port is published on all interfaces | **Partly remediated:** port bound to `127.0.0.1`; TLS is a deployment requirement |
| SEC-2 | Medium | Requests with missing or invalid tokens are not rate limited | Open (recommendation) |
| SEC-3 | Low | Administrator-supplied regex in policy patterns is evaluated on every request (ReDoS) | **Remediated** |
| SEC-4 | Low | Redis has no authentication; data stores are published on host loopback | Open (recommendation) |
| SEC-5 | Low | JWTs cannot be revoked; no audience validation | Open (recommendation) |
| SEC-6 | Low | `X-Upstream` response header discloses internal addresses | **Remediated** |
| SEC-7 | Low | MySQL TLS without certificate verification | Open (recommendation) |
| SEC-8 | Low | `/actuator/prometheus` is unauthenticated on the application port | Open (defence in depth) |
| SEC-9 | Info | Development token endpoint issues arbitrary roles (dev profile only) | Control confirmed |
| SEC-10 | Info | Secrets and test tokens: controls confirmed; one leftover local copy | **Leftover removed** |
| SEC-11 | Info | No dependency-vulnerability scan | Open (recommendation) |
| SEC-12 | Info | Error responses and logs: no sensitive data found | Control confirmed |

### SEC-1 — No TLS; API port published on all interfaces (Medium)
- **Evidence:** `infra/nginx/nginx.conf` has `listen 8080;` with no TLS. `docker-compose.yml` publishes
  `"${NGINX_HOST_PORT:-8080}:8080"` without a host address (all interfaces), unlike Grafana, Prometheus, Redis and
  MySQL, which are bound to `127.0.0.1`.
- **Impact:** bearer tokens (including admin tokens) cross the network in clear text; on a shared network other hosts
  can reach the API.
- **Remediation:** for the local demo, bind Nginx to `127.0.0.1` (one-line Compose change). For any real deployment,
  terminate TLS at Nginx (or the platform load balancer) and redirect HTTP to HTTPS; add HSTS.
- **Applied (2026-11-12):** `docker-compose.yml` publishes `"127.0.0.1:${NGINX_HOST_PORT:-8080}:8080"`. Verified:
  `docker ps` shows `127.0.0.1:8080->8080/tcp`; a request to the laptop's LAN address on port 8080 gets no
  connection; `localhost:8080` works and the smoke test passes. **Still open:** TLS (no change for the local demo).

### SEC-2 — Unauthenticated and invalid-token requests are not rate limited (Medium)
- **Evidence:** `RateLimitFilter` runs after `BearerTokenAuthenticationFilter` (D28). A request with an invalid or
  expired token is rejected with 401 before the limiter; documented as a known limitation since Phase 2.
- **Impact:** an attacker can send unlimited 401-producing requests. Each costs an HMAC verification (cheap), but
  the traffic is never throttled, so it can still exhaust connections and CPU.
- **Remediation:** a coarse per-IP edge limit in Nginx (`limit_req`) in front of the application, or an IP-keyed
  pre-limit before authentication (a design change, D28).

### SEC-3 — Policy patterns may contain administrator-supplied regex evaluated per request (Low)
- **Evidence:** `PolicyService` accepts any pattern Spring's `PathPatternParser` can parse
  (`ResolvedPolicy.parsePattern`), including `{var:regex}` with an arbitrary Java regex. `ResolvedPolicy.matches`
  evaluates it against the request path of every request. Observed in Phase 3B testing: the API accepted
  `/api/v1/demo/{n:[a-z&&[a-z]]+}` and applied it.
- **Impact:** a pathological regex (catastrophic backtracking) created by an administrator, by mistake or maliciously,
  lets any client burn CPU on every instance with crafted paths. It requires the `ADMIN` role.
- **Remediation:** reject `{var:regex}` in policy patterns (they are not needed for rate-limit scoping), or allow only
  a safe regex subset and bound the path length.
- **Applied (2026-11-12):** `ResolvedPolicy.parsePattern` rejects any capture variable containing `:` (Spring's regex
  syntax) with a 400 field error on `endpointPattern`. The same check runs when policies are loaded from MySQL (an
  invalid row is skipped with an error log) and for fallback policies. Supported syntax is unchanged: literals,
  `?`, `*`, `**`, `{var}`, `{*var}`. Compatibility: the five seeded policies and the dev fallback policies use only
  `/api/**` and literal paths; the live database held only those five. Tests: `ResolvedPolicyTest` (wildcards still
  compile and match; six regex forms rejected) and `PolicyApiIT.regexPathVariableIsRejected`; live check: `{n:[a-z]+}`
  and `{n:(a+)+b}` → 400, `prof?le` → 201.
- **Residual:** `*` and `?` inside a segment are still compiled by Spring into a regex for that segment (`.*`, `.`).
  Without nested quantifiers there is no exponential backtracking, but several `*` in one segment cost time
  polynomial in the segment length. Creating policies still requires `ADMIN`.

### SEC-4 — Redis without authentication; data stores on host loopback (Low)
- **Evidence:** `infra/redis/redis.conf` has `protected-mode no` and no `requirepass`/ACL; Redis is published on
  `127.0.0.1:6379`, MySQL on `127.0.0.1:3307`, Prometheus on `127.0.0.1:9090` (Prometheus without authentication).
- **Impact:** any process on the host can read or change rate-limit state (for example reset every quota) and read
  metrics. Not reachable from other hosts.
- **Remediation:** Redis ACL user + password (Lettuce `spring.data.redis.username/password` from the environment);
  do not publish data-store ports outside development.

### SEC-5 — JWTs cannot be revoked; no audience validation (Low)
- **Evidence:** the decoder validates signature (HS256 pinned), expiry/not-before (30 s skew) and issuer; there is no
  `aud` validator and no denylist (documented limitation). Tier and roles come from the token.
- **Impact:** a leaked token stays valid until it expires; a token issued for another service with the same issuer and
  secret would be accepted.
- **Remediation:** short token lifetimes; add an audience claim and `JwtClaimValidator` for `aud`; asymmetric keys
  (RS256/ES256 via JWKS) for multi-service deployments.

### SEC-6 — `X-Upstream` discloses internal addresses (Low)
- **Evidence:** `nginx.conf`: `add_header X-Upstream $upstream_addr always;`. Live response: `X-Upstream:
  172.28.0.12:8080`. `server_tokens off` hides the Nginx version (confirmed: `Server: nginx`).
- **Impact:** reveals internal topology. It is used by the smoke and benchmark tooling to check load distribution.
- **Remediation:** emit it only on a diagnostic path or behind a flag for testing; remove it in production.
- **Applied (2026-11-12):** the `add_header X-Upstream` line is removed from `infra/nginx/nginx.conf`. The tooling that
  used it now reads the serving instance from the Nginx access log (`upstream=` field), matching each probe by a
  unique `User-Agent` (`ratelimiter-probe/<marker>`): the smoke test's distribution check (AC10) and policy
  propagation probe (AC14), `scripts/bench/setup-policies.sh` probes and `runner.py`'s limiter-off check. Verified:
  responses carry no `X-Upstream` (`Server: nginx` only); smoke AC10 found 11 / 10 / 9 requests on app1 / app2 / app3;
  `setup-policies.sh apply` and `delete` confirmed all three instances. `runner.py`'s limiter-off check, run on the
  limiter-off stack variant, identified app1, app2 and app3 from the access log (200, no rate-limit header); on the
  normal stack it correctly refused (rate-limit headers present). The normal stack was restored and is healthy.

### SEC-7 — MySQL TLS without certificate verification (Low)
- **Evidence:** the base JDBC URL uses `sslMode=REQUIRED` (encrypted, server not authenticated); the dev profile
  disables TLS for the local container.
- **Impact:** a man-in-the-middle on the database network could impersonate MySQL. Inside the Compose network the
  risk is small.
- **Remediation:** `sslMode=VERIFY_IDENTITY` with the server CA for any deployment outside a single host.

### SEC-8 — `/actuator/prometheus` unauthenticated on the application port (Low)
- **Evidence:** `SecurityConfiguration` permits `/actuator/prometheus`; Nginx blocks `/actuator/**` (`location
  /actuator { return 404; }`) and the application ports are not published (decision P2).
- **Impact:** none in the current deployment; defence in depth only, if an application port were ever exposed.
- **Remediation:** a separate management port bound to the internal network, or authentication for scrapes.

### SEC-9 — Development token endpoint (Info)
- **Evidence:** `POST /api/v1/auth/demo-token` issues tokens with any tier and roles, including `ADMIN`. It is
  double-guarded: `@Profile("!prod")` and `ratelimiter.dev-endpoints.enabled` (default false), verified by
  `DevEndpointsDisabledIT`; the Compose stack runs the `prod` profile.
- **Recommendation:** keep it that way; never deploy the `dev` profile.

### SEC-10 — Secrets and test tokens (Info)
- **Confirmed controls:** `.env` git-ignored, Compose `:?` guards for every secret, `JWT_SECRET` must decode to ≥ 32
  bytes or startup fails, Grafana anonymous access and sign-up disabled, application container runs as uid 10001.
  Secret scan of the image (3A) and of every committed file (Phase 4): clean.
- **Handled in Phase 4:** expired test JWTs in 73 benchmark evidence files were redacted with approval (originals
  archived locally); the tooling no longer records tokens (D50).
- **Leftover removed (2026-11-12, approved):** the untracked, git-ignored folder `distributed-rate-limiter/` (an old
  session snapshot with expired test tokens in `load-tests/k6/.tokens/smoke-load.json`) was deleted after
  verification: no own `.git`, not a symlink; of its non-build files, 200 were identical to the repository and 29
  were older versions of repository files, none existed only there; no archives; the other 271 files were build
  output (`target/`). `git status` was identical before and after; all 6,257 benchmark checksums still match.
  Benchmark tokens in `load-tests/k6/.tokens/` are git-ignored.

### SEC-11 — No dependency-vulnerability scan (Info)
- **Evidence:** versions are current releases at build time (Spring Boot 3.5.16, Spring Security 6.5.x, Resilience4j
  2.4.0, springdoc 2.8.17; container images pinned to exact tags), but no CVE scan was run.
- **Recommendation:** enable GitHub Dependabot alerts and/or run OWASP Dependency-Check / `trivy` on the image in CI.

### SEC-12 — Error responses and logs (Info)
- **Evidence:** 429/503 bodies contain `type`, `title`, `status`, `error`, `message`, `detail`, `retryAfterSeconds`,
  `instance` (request path) and `timestamp`: no stack traces, internal policy ids or Redis details. Security headers
  are present (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store`). WARN logs
  name the policy and the root-cause exception class, but contain no tokens or client addresses.

## Confirmed controls (summary)

HS256 algorithm pinned; signature, expiry and issuer validated; stateless API (CSRF not applicable); admin role
required for policy management and `/stats`; `X-Forwarded-For` trusted only from the Nginx address (verified,
spoofed header ignored); tier never escalated from claims beyond the token; policy input validated by bean validation
and database constraints; dev endpoints and Swagger disabled in `prod`; health details hidden; `/actuator/**` blocked
at Nginx; application ports unpublished; non-root application container; no secrets in git or the image.

## Remediation status

| Option | Change | Status |
|---|---|---|
| A | SEC-1 (local part): bind Nginx to `127.0.0.1` in `docker-compose.yml` | Applied; docs/03 and docs/14 updated |
| B | SEC-3: reject `{var:regex}` in policy patterns (`ResolvedPolicy.parsePattern` + tests) | Applied; docs/07 and docs/08 updated |
| C | SEC-6: remove `X-Upstream`; tooling reads the Nginx access log instead | Applied; load-distribution checks preserved |
| D | SEC-10: delete the untracked `distributed-rate-limiter/` folder | Done after verification (no unique content) |
| — | SEC-2, SEC-4, SEC-5, SEC-7, SEC-8, SEC-11: deployment hardening | Documented recommendations; not needed for the local demo |

Verification after the changes (2026-11-12): `./mvnw clean verify` 318/318 (310 earlier + 8 new), stack smoke 37/37
on a rebuilt stack (`benchmarks/smoke/20261112-102259/`), benchmark tooling tests 49/49 and selftest 75/75, secret
scan of every changed and new file clean. The Phase 3B benchmark results were measured before these changes; none of
them is on the rate-limiting path (D51).
