package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.InvalidPolicyException;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;

class ResolvedPolicyTest {

    private static ResolvedPolicy compile(String method, String pattern, AlgorithmType algorithm, long limit, Integer refill) {
        return ResolvedPolicy.compile(1L, "p", method, pattern, null, KeyBy.USER, algorithm, limit, 60_000, refill, null, 0, 0);
    }

    @Test
    void compilesRuleWithRefillDefaultingToLimit() {
        assertThat(compile(null, "/api/**", AlgorithmType.TOKEN_BUCKET, 20, null).rule())
                .isEqualTo(RateLimitRule.tokenBucket(20, 20, Duration.ofMinutes(1)));
        assertThat(compile(null, "/api/**", AlgorithmType.TOKEN_BUCKET, 20, 5).rule().refillTokens()).isEqualTo(5);
        assertThat(compile(null, "/api/**", AlgorithmType.FIXED_WINDOW, 20, null).rule())
                .isEqualTo(RateLimitRule.fixedWindow(20, Duration.ofMinutes(1)));
    }

    @Test
    void rejectsInvalidDefinitions() {
        assertThatThrownBy(() -> compile(null, "/api/{", AlgorithmType.FIXED_WINDOW, 1, null))
                .isInstanceOf(InvalidPolicyException.class).extracting("field").isEqualTo("endpointPattern");
        assertThatThrownBy(() -> compile(null, "api/**", AlgorithmType.FIXED_WINDOW, 1, null))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> compile("FETCH", "/api/**", AlgorithmType.FIXED_WINDOW, 1, null))
                .isInstanceOf(InvalidPolicyException.class).extracting("field").isEqualTo("httpMethod");
        assertThatThrownBy(() -> compile(null, "/api/**", AlgorithmType.FIXED_WINDOW, 0, null))
                .isInstanceOf(InvalidPolicyException.class).extracting("field").isEqualTo("limits");
        assertThatThrownBy(() -> compile(null, "/api/**", AlgorithmType.SLIDING_WINDOW, 10, 5))
                .isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void supportedWildcardsStillCompileAndMatch() {
        assertMatches("/api/**", "/api/v1/demo/profile", true);
        assertMatches("/api/v1/demo/prof?le", "/api/v1/demo/profile", true);
        assertMatches("/api/*/demo/profile", "/api/v1/demo/profile", true);
        assertMatches("/api/v1/users/{id}/orders", "/api/v1/users/42/orders", true);
        assertMatches("/api/v1/files/{*path}", "/api/v1/files/a/b/c", true);
        assertMatches("/api/v1/demo/enterprise", "/api/v1/demo/enterprise", true);
        assertMatches("/api/v1/demo/prof?le", "/api/v1/demo/profiles", false);
        assertMatches("/api/v1/time:now", "/api/v1/time:now", true); // ':' outside a variable is a literal
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/demo/{n:[a-z]+}", "/api/{v:v[0-9]}/**", "/api/v1/{n:(a+)+b}",
            "/api/v1/{n:[a-z]{2}}", "/api/{id}/x/{n:.*}", "/api/v1/{:x}"})
    void rejectsRegexInPathVariables(String pattern) {
        assertThatThrownBy(() -> compile(null, pattern, AlgorithmType.FIXED_WINDOW, 1, null))
                .isInstanceOf(InvalidPolicyException.class).extracting("field").isEqualTo("endpointPattern");
    }

    private static void assertMatches(String pattern, String path, boolean expected) {
        assertThat(compile(null, pattern, AlgorithmType.FIXED_WINDOW, 1, null)
                .matches(HttpMethod.GET, PathContainer.parsePath(path), null)).as(pattern + " vs " + path).isEqualTo(expected);
    }

    @Test
    void userPoliciesKeyAuthenticatedCallersByUserAndAnonymousCallersByIp() {
        ResolvedPolicy perUser = compile(null, "/api/**", AlgorithmType.FIXED_WINDOW, 5, null);
        ResolvedPolicy perIp = ResolvedPolicy.compile(2L, "ip", null, "/api/**", null, KeyBy.IP,
                AlgorithmType.FIXED_WINDOW, 5, 60_000, null, null, 0, 0);
        ClientIdentity alice = new ClientIdentity("alice", Tier.PREMIUM, "203.0.113.1");
        ClientIdentity anonymous = ClientIdentity.anonymous("203.0.113.2");

        assertThat(PolicyResolver.keyFor(perUser, alice)).isEqualTo(RateLimitKey.user(1, "alice"));
        assertThat(PolicyResolver.keyFor(perUser, anonymous)).isEqualTo(RateLimitKey.ip(1, "203.0.113.2"));
        assertThat(PolicyResolver.keyFor(perIp, alice)).isEqualTo(RateLimitKey.ip(2, "203.0.113.1"));
    }

    @Test
    void identityInvariants() {
        assertThatThrownBy(() -> new ClientIdentity(null, Tier.FREE, "1.2.3.4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIdentity("u", Tier.ANONYMOUS, "1.2.3.4")).isInstanceOf(IllegalArgumentException.class);
    }
}
