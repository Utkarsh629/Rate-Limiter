package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.filter.RateLimitFilter;
import com.utkarsh.ratelimiter.filter.RateLimitFilterProperties;
import com.utkarsh.ratelimiter.filter.RateLimitHeaders;
import com.utkarsh.ratelimiter.filter.RateLimitResponseWriter;
import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.ClientIdentityResolver;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import com.utkarsh.ratelimiter.policy.RateLimitTarget;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** RateLimitFilter in isolation: Redis replaced by stub algorithms, policies by a mocked resolver. */
class RateLimitFilterTest {

    private static final RateLimitFilterProperties DEFAULTS = new RateLimitFilterProperties(true,
            List.of("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/api/v1/auth/**", "/error"),
            Duration.ofSeconds(5));

    private final PolicyResolver policies = mock(PolicyResolver.class);
    private final RateLimitAlgorithm tokenBucket = algorithm(AlgorithmType.TOKEN_BUCKET);
    private final AlgorithmRegistry registry = new AlgorithmRegistry(List.of(
            tokenBucket, algorithm(AlgorithmType.FIXED_WINDOW), algorithm(AlgorithmType.SLIDING_WINDOW)));
    private final ObjectMapper json = new ObjectMapper();

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/demo/profile");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    private static RateLimitAlgorithm algorithm(AlgorithmType type) {
        RateLimitAlgorithm algorithm = mock(RateLimitAlgorithm.class);
        when(algorithm.type()).thenReturn(type);
        return algorithm;
    }

    private static ResolvedPolicy policy(FailureMode failureMode) {
        return ResolvedPolicy.compile(7L, "free-tier", null, "/api/**", Tier.FREE, KeyBy.USER, AlgorithmType.TOKEN_BUCKET,
                20, 60_000, null, failureMode, 0, 0);
    }

    private RateLimitFilter filter(RateLimitFilterProperties properties, FailureMode defaultMode) {
        return new RateLimitFilter(new ClientIdentityResolver(), policies, registry, new RateLimitResponseWriter(json),
                properties, defaultMode);
    }

    private void run() throws Exception {
        filter(DEFAULTS, FailureMode.FAIL_OPEN).doFilter(request, response, chain);
    }

    /** Policy resolution delegates to the real key derivation, so USER vs IP keys are exercised. */
    private void policyApplies(ResolvedPolicy policy) {
        when(policies.resolve(any(), any(), any())).thenAnswer(invocation -> {
            ClientIdentity identity = invocation.getArgument(2);
            return Optional.of(new RateLimitTarget(policy, PolicyResolver.keyFor(policy, identity)));
        });
    }

    private void algorithmReturns(RateLimitResult result) {
        when(tokenBucket.tryAcquire(any(), any())).thenReturn(result);
    }

    private static void authenticateAs(String userId, Tier tier) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(userId).claim("tier", tier.name())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body() throws Exception {
        return json.readValue(response.getContentAsByteArray(), Map.class);
    }

    // ---------- allowed ----------

    @Test
    void allowedRequestContinuesWithRateLimitHeaders() throws Exception {
        authenticateAs("alice", Tier.FREE);
        policyApplies(policy(null));
        algorithmReturns(new RateLimitResult(true, 20, 19, 0, 3_000));

        run();

        assertThat(chain.getRequest()).as("chain continued").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(RateLimitHeaders.LIMIT)).isEqualTo("20");
        assertThat(response.getHeader(RateLimitHeaders.REMAINING)).isEqualTo("19");
        assertThat(response.getHeader(RateLimitHeaders.RESET)).isEqualTo("3");
        assertThat(response.getHeader(RateLimitHeaders.RETRY_AFTER)).isNull();
        verify(tokenBucket).tryAcquire(eq(RateLimitKey.user(7, "alice")), any());
    }

    @Test
    void anonymousCallerIsLimitedByIp() throws Exception {
        request.setRemoteAddr("198.51.100.23");
        policyApplies(policy(null));
        algorithmReturns(new RateLimitResult(true, 20, 19, 0, 3_000));

        run();

        verify(policies).resolve(eq(HttpMethod.GET), eq("/api/v1/demo/profile"), eq(ClientIdentity.anonymous("198.51.100.23")));
        verify(tokenBucket).tryAcquire(eq(RateLimitKey.ip(7, "198.51.100.23")), any());
    }

    // ---------- rejected ----------

    @Test
    void rejectedRequestGets429HeadersAndJsonAndNeverReachesTheController() throws Exception {
        authenticateAs("alice", Tier.FREE);
        policyApplies(policy(null));
        algorithmReturns(new RateLimitResult(false, 20, 0, 2_500, 60_000));

        run();

        assertThat(chain.getRequest()).as("chain must not continue").isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(RateLimitHeaders.LIMIT)).isEqualTo("20");
        assertThat(response.getHeader(RateLimitHeaders.REMAINING)).isEqualTo("0");
        assertThat(response.getHeader(RateLimitHeaders.RESET)).isEqualTo("60");
        assertThat(response.getHeader(RateLimitHeaders.RETRY_AFTER)).isEqualTo("3");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body()).containsEntry("status", 429).containsEntry("error", "Too Many Requests")
                .containsEntry("message", "Rate limit exceeded").containsEntry("retryAfterSeconds", 3)
                .containsEntry("title", "Too Many Requests").containsEntry("instance", "/api/v1/demo/profile");
    }

    @ParameterizedTest(name = "retry after {0} ms -> Retry-After {1}")
    @CsvSource({"1, 1", "999, 1", "1000, 1", "1001, 2", "14200, 15", "60000, 60"})
    void retryAfterIsRoundedUpToWholeSeconds(long retryMillis, String expected) throws Exception {
        policyApplies(policy(null));
        algorithmReturns(new RateLimitResult(false, 20, 0, retryMillis, 60_000));

        run();

        assertThat(response.getHeader(RateLimitHeaders.RETRY_AFTER)).isEqualTo(expected);
        assertThat(body().get("retryAfterSeconds")).isEqualTo(Integer.parseInt(expected));
    }

    @Test
    void headerConversionsAreConsistent() {
        assertThat(RateLimitHeaders.resetSeconds(0)).isZero();
        assertThat(RateLimitHeaders.resetSeconds(1)).isEqualTo(1);
        assertThat(RateLimitHeaders.retryAfterSeconds(0)).isEqualTo(1);
    }

    // ---------- requests that are not rate limited ----------

    @ParameterizedTest
    @CsvSource({"/actuator/health/readiness", "/actuator/prometheus", "/v3/api-docs", "/swagger-ui/index.html",
            "/api/v1/auth/demo-token", "/error"})
    void excludedPathsBypassTheLimiterEntirely(String path) throws Exception {
        request.setRequestURI(path);
        policyApplies(policy(null));

        run();

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader(RateLimitHeaders.LIMIT)).isNull();
        verifyNoInteractions(policies);
    }

    @Test
    void requestWithNoMatchingPolicyContinuesWithoutHeaders() throws Exception {
        when(policies.resolve(any(), any(), any())).thenReturn(Optional.empty());

        run();

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader(RateLimitHeaders.LIMIT)).isNull();
        verify(tokenBucket, never()).tryAcquire(any(), any());
    }

    @Test
    void disabledFilterDoesNothing() throws Exception {
        policyApplies(policy(null));
        RateLimitFilterProperties off = new RateLimitFilterProperties(false, DEFAULTS.excludedPaths(), Duration.ofSeconds(5));

        filter(off, FailureMode.FAIL_OPEN).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(policies);
    }

    // ---------- Redis failures ----------

    @Test
    void storeFailureWithFailOpenContinuesWithoutRateLimitHeaders() throws Exception {
        policyApplies(policy(null)); // no override -> global default FAIL_OPEN
        when(tokenBucket.tryAcquire(any(), any())).thenThrow(new RateLimitStoreException("timeout"));

        run();

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(RateLimitHeaders.LIMIT)).as("quota unknown: no invented numbers").isNull();
    }

    @Test
    void storeFailureWithFailClosedPolicyReturns503NotA429() throws Exception {
        policyApplies(policy(FailureMode.FAIL_CLOSED));
        when(tokenBucket.tryAcquire(any(), any())).thenThrow(new RateLimitStoreException("connection refused"));

        run();

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(RateLimitHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getHeader(RateLimitHeaders.LIMIT)).isNull();
        assertThat(body()).containsEntry("status", 503).containsEntry("error", "Service Unavailable")
                .containsEntry("retryAfterSeconds", 5);
    }

    @Test
    void globalDefaultFailClosedAppliesWhenThePolicyHasNoOverride() throws Exception {
        policyApplies(policy(null));
        when(tokenBucket.tryAcquire(any(), any())).thenThrow(new RateLimitStoreException("down"));

        filter(DEFAULTS, FailureMode.FAIL_CLOSED).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void policyFailOpenOverridesAGlobalFailClosedDefault() throws Exception {
        policyApplies(policy(FailureMode.FAIL_OPEN));
        when(tokenBucket.tryAcquire(any(), any())).thenThrow(new RateLimitStoreException("down"));

        filter(DEFAULTS, FailureMode.FAIL_CLOSED).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    // ---------- 2D: every breaker situation x every failure-mode configuration (design §8) ----------

    enum BreakerSituation {
        CLOSED_INFRASTRUCTURE_ERROR, OPEN_NOT_PERMITTED, HALF_OPEN_NOT_PERMITTED, HALF_OPEN_PROBE_FAILED;

        RuntimeException exception() {
            return switch (this) {
                case CLOSED_INFRASTRUCTURE_ERROR, HALF_OPEN_PROBE_FAILED -> new RateLimitStoreException("Redis script failed",
                        new org.springframework.dao.QueryTimeoutException("Redis command timed out"));
                case OPEN_NOT_PERMITTED -> new com.utkarsh.ratelimiter.redis.RateLimitStoreUnavailableException(
                        "Redis circuit breaker is OPEN; Redis not called");
                case HALF_OPEN_NOT_PERMITTED -> new com.utkarsh.ratelimiter.redis.RateLimitStoreUnavailableException(
                        "Redis circuit breaker is HALF_OPEN; Redis not called");
            };
        }
    }

    @ParameterizedTest(name = "{0} | policy {1} | default {2} -> {3}")
    @CsvSource({
            "CLOSED_INFRASTRUCTURE_ERROR, FAIL_OPEN,   FAIL_CLOSED, 200",
            "CLOSED_INFRASTRUCTURE_ERROR, FAIL_CLOSED, FAIL_OPEN,   503",
            "CLOSED_INFRASTRUCTURE_ERROR, ,            FAIL_OPEN,   200",
            "CLOSED_INFRASTRUCTURE_ERROR, ,            FAIL_CLOSED, 503",
            "OPEN_NOT_PERMITTED,          FAIL_OPEN,   FAIL_CLOSED, 200",
            "OPEN_NOT_PERMITTED,          FAIL_CLOSED, FAIL_OPEN,   503",
            "OPEN_NOT_PERMITTED,          ,            FAIL_OPEN,   200",
            "OPEN_NOT_PERMITTED,          ,            FAIL_CLOSED, 503",
            "HALF_OPEN_NOT_PERMITTED,     FAIL_OPEN,   FAIL_CLOSED, 200",
            "HALF_OPEN_NOT_PERMITTED,     FAIL_CLOSED, FAIL_OPEN,   503",
            "HALF_OPEN_NOT_PERMITTED,     ,            FAIL_OPEN,   200",
            "HALF_OPEN_NOT_PERMITTED,     ,            FAIL_CLOSED, 503",
            "HALF_OPEN_PROBE_FAILED,      FAIL_OPEN,   FAIL_CLOSED, 200",
            "HALF_OPEN_PROBE_FAILED,      FAIL_CLOSED, FAIL_OPEN,   503",
            "HALF_OPEN_PROBE_FAILED,      ,            FAIL_OPEN,   200",
            "HALF_OPEN_PROBE_FAILED,      ,            FAIL_CLOSED, 503",
    })
    void everyBreakerSituationFollowsTheEffectiveFailureMode(BreakerSituation situation, FailureMode policyMode,
                                                             FailureMode defaultMode, int expectedStatus) throws Exception {
        policyApplies(policy(policyMode));
        when(tokenBucket.tryAcquire(any(), any())).thenThrow(situation.exception());

        filter(DEFAULTS, defaultMode).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(expectedStatus).isNotEqualTo(429);
        assertThat(response.getHeader(RateLimitHeaders.LIMIT)).as("store not consulted: no rate-limit headers").isNull();
        assertThat(response.getHeader(RateLimitHeaders.REMAINING)).isNull();
        if (expectedStatus == 503) {
            assertThat(chain.getRequest()).as("controller not reached").isNull();
            assertThat(response.getHeader(RateLimitHeaders.RETRY_AFTER)).isEqualTo("5");
            assertThat(body()).containsEntry("status", 503).containsEntry("retryAfterSeconds", 5);
        } else {
            assertThat(chain.getRequest()).as("controller reached").isNotNull();
            assertThat(response.getHeader(RateLimitHeaders.RETRY_AFTER)).isNull();
        }
    }

    @Test
    void unexpectedErrorsAreNotSwallowedIntoFailOpen() {
        // Only rate-limit-store failures trigger the failure mode; a programming error must surface.
        policyApplies(policy(null));
        when(tokenBucket.tryAcquire(any(), any())).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
        assertThat(chain.getRequest()).isNull();
    }
}
