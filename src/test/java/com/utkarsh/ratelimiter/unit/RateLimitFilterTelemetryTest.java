package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.filter.RateLimitFilter;
import com.utkarsh.ratelimiter.filter.RateLimitFilterProperties;
import com.utkarsh.ratelimiter.filter.RateLimitResponseWriter;
import com.utkarsh.ratelimiter.identity.ClientIdentityResolver;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.metrics.RateLimitTelemetry;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import com.utkarsh.ratelimiter.policy.RateLimitTarget;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Decision P1: telemetry is observation only. Each decided request is recorded exactly once with the right
 * result, undecided requests are not recorded, and responses are byte-for-byte identical with and without it.
 */
class RateLimitFilterTelemetryTest {

    private static final RateLimitFilterProperties PROPS = new RateLimitFilterProperties(true,
            List.of("/actuator/**", "/api/v1/auth/**", "/error"), Duration.ofSeconds(5));

    private final PolicyResolver policies = mock(PolicyResolver.class);
    private final RateLimitAlgorithm tokenBucket = algorithm(AlgorithmType.TOKEN_BUCKET);
    private final AlgorithmRegistry registry = new AlgorithmRegistry(List.of(
            tokenBucket, algorithm(AlgorithmType.FIXED_WINDOW), algorithm(AlgorithmType.SLIDING_WINDOW)));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RateLimitTelemetry telemetry = new RateLimitTelemetry(meters, null);

    private static RateLimitAlgorithm algorithm(AlgorithmType type) {
        RateLimitAlgorithm a = mock(RateLimitAlgorithm.class);
        when(a.type()).thenReturn(type);
        return a;
    }

    private void policyApplies(FailureMode mode) {
        ResolvedPolicy policy = ResolvedPolicy.compile(7L, "p", null, "/api/**", null, KeyBy.IP, AlgorithmType.TOKEN_BUCKET,
                20, 60_000, null, mode, 0, 0);
        when(policies.resolve(any(), any(), any())).thenAnswer(i -> Optional.of(
                new RateLimitTarget(policy, PolicyResolver.keyFor(policy, i.getArgument(2)))));
    }

    private MockHttpServletResponse run(RateLimitTelemetry t, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("198.51.100.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RateLimitFilter(new ClientIdentityResolver(), policies, registry, new RateLimitResponseWriter(new ObjectMapper()),
                PROPS, FailureMode.FAIL_OPEN, t).doFilter(request, response, new MockFilterChain());
        return response;
    }

    private double count(String result) {
        var c = meters.find("ratelimiter.requests").tags("algorithm", "token_bucket", "result", result).counter();
        return c == null ? 0 : c.count();
    }

    @ParameterizedTest(name = "{0} -> result={1}")
    @CsvSource({"ALLOWED, allowed", "REJECTED, rejected", "STORE_ERROR_OPEN, fail_open", "STORE_ERROR_CLOSED, fail_closed"})
    void eachDecisionIsRecordedOnceAndResponsesAreUnchanged(String scenario, String expectedResult) throws Exception {
        Supplier<Object> stub = () -> null;
        switch (scenario) {
            case "ALLOWED" -> { policyApplies(null); when(tokenBucket.tryAcquire(any(), any())).thenReturn(new RateLimitResult(true, 20, 19, 0, 3_000)); }
            case "REJECTED" -> { policyApplies(null); when(tokenBucket.tryAcquire(any(), any())).thenReturn(new RateLimitResult(false, 20, 0, 3_000, 60_000)); }
            case "STORE_ERROR_OPEN" -> { policyApplies(FailureMode.FAIL_OPEN); when(tokenBucket.tryAcquire(any(), any())).thenThrow(new RateLimitStoreException("x")); }
            default -> { policyApplies(FailureMode.FAIL_CLOSED); when(tokenBucket.tryAcquire(any(), any())).thenThrow(new RateLimitStoreException("x")); }
        }

        MockHttpServletResponse withTelemetry = run(telemetry, "/api/v1/demo/profile");
        MockHttpServletResponse without = run(null, "/api/v1/demo/profile");

        assertThat(count(expectedResult)).isEqualTo(1);
        assertThat(meters.find("ratelimiter.requests").counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(1);
        assertThat(meters.find("ratelimiter.request.duration").tags("algorithm", "token_bucket", "result", expectedResult).timer().count()).isEqualTo(1);
        assertThat(withTelemetry.getStatus()).isEqualTo(without.getStatus());
        assertThat(withTelemetry.getHeaderNames()).containsExactlyInAnyOrderElementsOf(without.getHeaderNames());
        for (String h : List.of("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After")) {
            assertThat(withTelemetry.getHeader(h)).isEqualTo(without.getHeader(h));
        }
        if (withTelemetry.getContentLength() > 0) {
            Map<?, ?> a = new ObjectMapper().readValue(withTelemetry.getContentAsByteArray(), Map.class);
            Map<?, ?> b = new ObjectMapper().readValue(without.getContentAsByteArray(), Map.class);
            a.remove("timestamp");
            b.remove("timestamp");
            assertThat(a).isEqualTo(b);
        }
    }

    @Test
    void undecidedRequestsAreNotRecorded() throws Exception {
        when(policies.resolve(any(), any(), any())).thenReturn(Optional.empty());
        run(telemetry, "/api/v1/demo/profile");       // no policy
        run(telemetry, "/actuator/health/readiness"); // excluded
        assertThat(meters.find("ratelimiter.requests").counters().stream().mapToDouble(c -> c.count()).sum()).isZero();
    }
}
