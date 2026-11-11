package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import com.utkarsh.ratelimiter.policy.RateLimitTarget;
import com.utkarsh.ratelimiter.support.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Identity (JWT subject + tier, client IP) and tier-based policy resolution end to end, plus the
 * hand-off to the existing AlgorithmRegistry that the rate-limiting filter will use.
 */
class IdentityAndResolutionIT extends ApiTestSupport {

    private static final String WHOAMI = "/api/v1/demo/whoami";

    @Autowired PolicyResolver resolver;
    @Autowired AlgorithmRegistry registry;

    @Test
    void anonymousCallerGetsAnonymousTierAndIsKeyedByIp() {
        Map<String, Object> me = callForMap(HttpMethod.GET, WHOAMI, null, null).getBody();

        assertThat(me).containsEntry("userId", null).containsEntry("tier", "ANONYMOUS")
                .containsEntry("policyName", "anonymous-default").containsEntry("algorithm", "SLIDING_WINDOW");
        assertThat((String) me.get("quotaSubject")).isEqualTo("IP:" + me.get("clientIp"));
        assertThat((String) me.get("clientIp")).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
    }

    @Test
    void eachTierResolvesToItsSeededPolicyAndIsKeyedByUser() {
        for (Tier tier : List.of(Tier.FREE, Tier.PREMIUM, Tier.ENTERPRISE)) {
            Map<String, Object> me = callForMap(HttpMethod.GET, WHOAMI, tokens.user("user-" + tier, tier), null).getBody();
            assertThat(me).containsEntry("userId", "user-" + tier).containsEntry("tier", tier.name())
                    .containsEntry("policyName", tier.name().toLowerCase() + "-tier")
                    .containsEntry("quotaSubject", "USER:user-" + tier);
        }
    }

    @Test
    void tierClaimCannotEscalate() {
        Map<String, Object> me = callForMap(HttpMethod.GET, WHOAMI, tokens.withClaims("sneaky", Map.of("tier", "PLATINUM")), null).getBody();
        assertThat(me).containsEntry("tier", "FREE").containsEntry("policyName", "free-tier");
    }

    @Test
    void invalidTokenIsRejectedNotDowngradedToAnonymous() {
        assertThat(callForMap(HttpMethod.GET, WHOAMI, tokens.adminSignedWithAnotherKey(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void spoofedXForwardedForIsIgnoredWithoutTrustedProxies() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "6.6.6.6");
        @SuppressWarnings({"rawtypes", "unchecked"})
        ResponseEntity<Map<String, Object>> response = (ResponseEntity) http.exchange(WHOAMI, HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), Map.class);

        assertThat(response.getBody().get("clientIp")).isNotEqualTo("6.6.6.6");
    }

    @Test
    void demoTokenEndpointMintsUsableTokens() {
        Map<String, Object> minted = callForMap(HttpMethod.POST, "/api/v1/auth/demo-token", null,
                Map.of("userId", "demo-admin", "tier", "PREMIUM", "roles", List.of("ADMIN"), "ttlSeconds", 120)).getBody();

        assertThat(minted).containsEntry("tokenType", "Bearer").containsEntry("expiresIn", 120);
        String token = (String) minted.get("accessToken");
        assertThat(callForMap(HttpMethod.GET, "/api/v1/policies", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(callForMap(HttpMethod.GET, WHOAMI, token, null).getBody()).containsEntry("tier", "PREMIUM");
    }

    @Test
    void demoTokenEndpointValidatesItsInput() {
        assertThat(callForMap(HttpMethod.POST, "/api/v1/auth/demo-token", null,
                Map.of("userId", "x", "tier", "GOLD")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(callForMap(HttpMethod.POST, "/api/v1/auth/demo-token", null,
                Map.of("userId", "x", "tier", "FREE", "roles", List.of("ROOT"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(callForMap(HttpMethod.POST, "/api/v1/auth/demo-token", null,
                Map.of("userId", "bad user", "tier", "FREE")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resolvedTargetDrivesTheAlgorithmRegistry() {
        // FREE tier: token bucket, 20 per minute, keyed by user. No database or HTTP filter involved.
        ClientIdentity user = new ClientIdentity("registry-" + UUID.randomUUID(), Tier.FREE, "203.0.113.50");
        RateLimitTarget target = resolver.resolve(HttpMethod.GET, "/api/v1/demo/hello", user).orElseThrow();
        assertThat(target.policy().name()).isEqualTo("free-tier");

        int allowed = 0;
        RateLimitResult last = null;
        for (int i = 0; i < 25; i++) {
            last = registry.tryAcquire(target.key(), target.policy().rule());
            allowed += last.allowed() ? 1 : 0;
        }
        assertThat(allowed).isEqualTo(20);
        assertThat(last.allowed()).isFalse();
        assertThat(last.retryAfterMillis()).isBetween(1L, 3_000L); // one token per 3 s
    }

    @Test
    void moreSpecificPolicyOverridesTierDefaultForItsEndpoint() {
        Map<String, Object> specific = new java.util.HashMap<>(Map.of(
                "name", "it-free-orders", "endpointPattern", "/api/v1/orders/**", "tier", "FREE", "keyBy", "USER",
                "algorithm", "FIXED_WINDOW", "limit", 2, "windowMs", 60_000));
        assertThat(callForMap(HttpMethod.POST, "/api/v1/policies", tokens.admin(), specific).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        org.awaitility.Awaitility.await().until(() -> policyCache.snapshot().policies().stream().anyMatch(p -> p.name().equals("it-free-orders")));

        ClientIdentity user = new ClientIdentity("orders-" + UUID.randomUUID(), Tier.FREE, "203.0.113.51");
        assertThat(resolver.resolve(HttpMethod.GET, "/api/v1/orders/42", user).orElseThrow().policy().name()).isEqualTo("it-free-orders");
        assertThat(resolver.resolve(HttpMethod.GET, "/api/v1/users/42", user).orElseThrow().policy().name()).isEqualTo("free-tier");
        assertThat(resolver.resolve(HttpMethod.GET, "/api/v1/orders/42", new ClientIdentity("p", Tier.PREMIUM, "1.1.1.1"))
                .orElseThrow().policy().name()).isEqualTo("premium-tier");
    }
}
