package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utkarsh.ratelimiter.filter.RateLimitHeaders;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.support.ApiTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The full HTTP pipeline on a real server: Spring Security (JWT) → RateLimitFilter → policy snapshot
 * → AlgorithmRegistry → Redis Lua → response, against Testcontainers MySQL and Redis.
 *
 * <p>Tier quota tests temporarily slow the seeded policies' refill to 1 token per day. The capacity,
 * which is the quota under test, is unchanged; without this, continuous refill (one PREMIUM token
 * every 600 ms) could add a token mid-test and make the exact count timing-dependent.
 */
class RateLimitFilterIT extends ApiTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Object[]> originalRefill = new HashMap<>();

    private void slowRefill(String... policyNames) {
        for (String name : policyNames) {
            Map<String, Object> row = jdbc.queryForMap("SELECT refill_tokens, window_ms FROM rate_limit_policy WHERE name = ?", name);
            originalRefill.put(name, new Object[] {row.get("refill_tokens"), row.get("window_ms")});
            jdbc.update("UPDATE rate_limit_policy SET refill_tokens = 1, window_ms = 86400000 WHERE name = ?", name);
        }
        policyCache.reload("test: slow refill");
    }

    @AfterEach
    void restoreRefill() {
        if (!originalRefill.isEmpty()) {
            originalRefill.forEach((name, values) ->
                    jdbc.update("UPDATE rate_limit_policy SET refill_tokens = ?, window_ms = ? WHERE name = ?", values[0], values[1], name));
            policyCache.reload("test: restore refill");
        }
    }

    private static String header(ResponseEntity<?> response, String name) {
        return response.getHeaders().getFirst(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(ResponseEntity<String> response) throws Exception {
        return JSON.readValue(response.getBody(), Map.class);
    }

    /** Sends requests until the first 429; returns how many were allowed and asserts the counting is exact. */
    private int allowedUntilRejected(String path, String token, int limit) {
        int allowed = 0;
        for (int i = 0; i < limit + 1; i++) {
            ResponseEntity<String> response = get(path, token);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(header(response, RateLimitHeaders.LIMIT)).isEqualTo(Integer.toString(limit));
            assertThat(header(response, RateLimitHeaders.REMAINING)).isEqualTo(Integer.toString(limit - 1 - i));
            allowed++;
        }
        assertThat(get(path, token).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        return allowed;
    }

    // ---------- anonymous / IP ----------

    @Test
    void anonymousCallersAreLimitedPerIp() throws Exception {
        assertThat(allowedUntilRejected("/api/v1/demo/public", null, 10)).isEqualTo(10);

        ResponseEntity<String> rejected = get("/api/v1/demo/public", null);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getContentType().isCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        Map<String, Object> body = json(rejected);
        assertThat(body).containsEntry("status", 429).containsEntry("error", "Too Many Requests")
                .containsEntry("message", "Rate limit exceeded").containsEntry("instance", "/api/v1/demo/public")
                .doesNotContainKey("endpoint"); // the controller never ran
        assertThat(header(rejected, RateLimitHeaders.RETRY_AFTER)).isEqualTo(body.get("retryAfterSeconds").toString());
        assertThat(header(rejected, RateLimitHeaders.REMAINING)).isEqualTo("0");
    }

    @Test
    void spoofedForwardedForDoesNotBuyANewQuota() {
        allowedUntilRejected("/api/v1/demo/public", null, 10);

        HttpHeaders spoofed = new HttpHeaders();
        spoofed.add("X-Forwarded-For", "203.0.113." + (int) (Math.random() * 200));
        ResponseEntity<String> response = http.exchange("/api/v1/demo/public", HttpMethod.GET, new HttpEntity<>(spoofed), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ---------- tiers ----------

    @Test
    void freeUserGetsTheFreeQuota() {
        slowRefill("free-tier");
        assertThat(allowedUntilRejected("/api/v1/demo/profile", tokens.user("free-" + UUID.randomUUID(), Tier.FREE), 20)).isEqualTo(20);
    }

    @Test
    void premiumUserGetsThePremiumQuota() {
        slowRefill("premium-tier");
        assertThat(allowedUntilRejected("/api/v1/demo/premium", tokens.user("prem-" + UUID.randomUUID(), Tier.PREMIUM), 100)).isEqualTo(100);
    }

    @Test
    void enterpriseUserGetsTheEnterpriseQuota() {
        slowRefill("enterprise-tier");
        assertThat(allowedUntilRejected("/api/v1/demo/profile", tokens.user("ent-" + UUID.randomUUID(), Tier.ENTERPRISE), 1000)).isEqualTo(1000);
    }

    @Test
    void endpointSpecificPolicyBeatsTheTierDefault() {
        // demo-enterprise-endpoint (V3): sliding window, 50/min, for GET /api/v1/demo/enterprise only
        String token = tokens.user("ent-" + UUID.randomUUID(), Tier.ENTERPRISE);
        assertThat(allowedUntilRejected("/api/v1/demo/enterprise", token, 50)).isEqualTo(50);
        // The same user is untouched on other endpoints, where the tier default (1000) applies.
        assertThat(header(get("/api/v1/demo/profile", token), RateLimitHeaders.LIMIT)).isEqualTo("1000");
    }

    @Test
    void usersHaveIndependentQuotas() {
        slowRefill("free-tier");
        String alice = tokens.user("alice-" + UUID.randomUUID(), Tier.FREE);
        allowedUntilRejected("/api/v1/demo/profile", alice, 20);

        ResponseEntity<String> bob = get("/api/v1/demo/profile", tokens.user("bob-" + UUID.randomUUID(), Tier.FREE));
        assertThat(bob.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(header(bob, RateLimitHeaders.REMAINING)).isEqualTo("19");
    }

    // ---------- headers per algorithm ----------

    @Test
    void tokenBucketHeadersFollowTheRefillRate() throws Exception {
        // free-tier as seeded: 20 tokens, refill 20/min = one token every 3 s
        String token = tokens.user("tb-" + UUID.randomUUID(), Tier.FREE);
        ResponseEntity<String> first = get("/api/v1/demo/profile", token);
        assertThat(header(first, RateLimitHeaders.RESET)).isEqualTo("3"); // one token short of full
        for (int i = 0; i < 19; i++) {
            get("/api/v1/demo/profile", token);
        }
        ResponseEntity<String> rejected = get("/api/v1/demo/profile", token);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(header(rejected, RateLimitHeaders.RETRY_AFTER))).isBetween(1L, 3L);
        assertThat(Long.parseLong(header(rejected, RateLimitHeaders.RESET))).isBetween(57L, 60L);
        assertThat(json(rejected).get("retryAfterSeconds").toString()).isEqualTo(header(rejected, RateLimitHeaders.RETRY_AFTER));
    }

    @Test
    void fixedWindowRetryAfterIsTheEndOfTheWindow() {
        Map<String, Object> fixed = new HashMap<>(Map.of("name", "it-fixed-public", "httpMethod", "GET",
                "endpointPattern", "/api/v1/demo/public", "tier", "FREE", "keyBy", "USER",
                "algorithm", "FIXED_WINDOW", "limit", 3, "windowMs", 60_000));
        assertThat(callForMap(HttpMethod.POST, "/api/v1/policies", tokens.admin(), fixed).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().until(() -> policyCache.snapshot().policies().stream().anyMatch(p -> p.name().equals("it-fixed-public")));

        String token = tokens.user("fw-" + UUID.randomUUID(), Tier.FREE);
        assertThat(allowedUntilRejected("/api/v1/demo/public", token, 3)).isEqualTo(3);
        ResponseEntity<String> rejected = get("/api/v1/demo/public", token);
        long retryAfter = Long.parseLong(header(rejected, RateLimitHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(header(rejected, RateLimitHeaders.RESET)).isEqualTo(Long.toString(retryAfter)); // both = window end
    }

    // ---------- what is and is not rate limited ----------

    @Test
    void excludedEndpointsAreNeverLimitedAndCarryNoHeaders() {
        for (int i = 0; i < 25; i++) {
            ResponseEntity<String> health = http.getForEntity("/actuator/health/readiness", String.class);
            assertThat(header(health, RateLimitHeaders.LIMIT)).isNull();
            ResponseEntity<String> token = http.postForEntity("/api/v1/auth/demo-token", Map.of("userId", "x", "tier", "FREE"), String.class);
            assertThat(token.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(header(token, RateLimitHeaders.LIMIT)).isNull();
        }
    }

    // ---------- filter ordering ----------

    @Test
    void filterRunsAfterAuthenticationSoTheTokenIdentityIsUsed() {
        // Authenticated FREE user on /public: the FREE policy (20), not the anonymous per-IP policy (10).
        assertThat(header(get("/api/v1/demo/public", tokens.user("u-" + UUID.randomUUID(), Tier.FREE)), RateLimitHeaders.LIMIT))
                .isEqualTo("20");
        assertThat(header(get("/api/v1/demo/public", null), RateLimitHeaders.LIMIT)).isEqualTo("10");
    }

    @Test
    void filterRunsBeforeAuthorizationSoRefusedRequestsStillUseQuota() {
        // Anonymous calls to the admin API: 401 each time, but each one counts against the IP quota.
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = get("/api/v1/policies", null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(header(response, RateLimitHeaders.REMAINING)).isEqualTo(Integer.toString(9 - i));
        }
        assertThat(get("/api/v1/policies", null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Wrong tier for the endpoint: 403, also counted.
        ResponseEntity<String> forbidden = get("/api/v1/demo/premium", tokens.user("f-" + UUID.randomUUID(), Tier.FREE));
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(header(forbidden, RateLimitHeaders.REMAINING)).isEqualTo("19");
    }

    @Test
    void invalidTokensAreRejectedByAuthenticationBeforeTheLimiter() {
        // Documented limitation: bad tokens never reach the limiter (cheap HMAC check, no Redis call).
        ResponseEntity<String> response = get("/api/v1/demo/profile", tokens.adminSignedWithAnotherKey());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(header(response, RateLimitHeaders.LIMIT)).isNull();
    }

    @Test
    void eachRequestIsCountedExactlyOnce() {
        // A 404 goes through an ERROR re-dispatch; it must not be counted twice.
        String token = tokens.user("once-" + UUID.randomUUID(), Tier.FREE);
        assertThat(get("/api/v1/demo/does-not-exist", token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(header(get("/api/v1/demo/profile", token), RateLimitHeaders.REMAINING)).isEqualTo("18");
        List.of(1, 2).forEach(i -> get("/api/v1/demo/profile", token));
        assertThat(header(get("/api/v1/demo/profile", token), RateLimitHeaders.REMAINING)).isEqualTo("15");
    }
}
