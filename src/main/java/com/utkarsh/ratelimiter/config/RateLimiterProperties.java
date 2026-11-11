package com.utkarsh.ratelimiter.config;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import jakarta.validation.constraints.Positive;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated {@code ratelimiter.*} configuration. Invalid values fail application startup
 * rather than surfacing later as wrong limiting behaviour.
 *
 * @param instanceId         identifies this instance in per-instance statistics shards and logs;
 *                           must be unique per running instance
 * @param keyPrefix          prefix for every Redis key this application writes
 * @param defaultFailureMode used when a policy does not set its own failure mode
 * @param policy             policy cache settings
 * @param stats              statistics shard settings
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String instanceId,
        @DefaultValue("rl") @NotBlank @Pattern(regexp = "[a-z0-9]{1,16}") String keyPrefix,
        @DefaultValue("FAIL_OPEN") @NotNull FailureMode defaultFailureMode,
        @DefaultValue @Valid @NotNull Policy policy,
        @DefaultValue @Valid @NotNull Stats stats) {

    /**
     * @param reloadInterval  full reload of the in-memory policy snapshot from MySQL; safety net for
     *                        missed pub/sub invalidation messages
     * @param fallbackEnabled serve configuration-defined fallback policies if MySQL is unreachable at
     *                        startup. Development only: production must not serve possibly stale
     *                        policies and instead reports itself not ready.
     */
    public record Policy(
            @DefaultValue("30s") @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 10) Duration reloadInterval,
            @DefaultValue("false") boolean fallbackEnabled,
            @DefaultValue @Valid List<FallbackPolicy> fallbackPolicies) {
    }

    /**
     * A policy served only when {@code fallback-enabled} is true and the database has never been
     * reachable since startup. Ids must not collide with database ids (use a high range).
     */
    public record FallbackPolicy(
            @Positive long id,
            @NotBlank String name,
            String httpMethod,
            @NotBlank String endpointPattern,
            Tier tier,
            @NotNull KeyBy keyBy,
            @NotNull AlgorithmType algorithm,
            @Positive long limit,
            @Positive long windowMs,
            Integer refillTokens,
            FailureMode failureMode,
            @DefaultValue("0") int priority) {

        public ResolvedPolicy toResolved() {
            return ResolvedPolicy.compile(id, name, httpMethod, endpointPattern, tier, keyBy, algorithm, limit,
                    windowMs, refillTokens, failureMode, priority, 0);
        }
    }

    /**
     * @param flushInterval how often each instance flushes its in-memory allowed/rejected counters to
     *                      its own Redis statistics shard. Counts not yet flushed are lost if the
     *                      instance dies.
     */
    public record Stats(
            @DefaultValue("5s") @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 5) Duration flushInterval) {
    }
}
