package com.utkarsh.ratelimiter.metrics;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.stats.PolicyStatsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Records one rate-limit decision: Micrometer meters (3A.1) and per-policy statistics (3A.2).
 * Called once per decided request by {@code RateLimitFilter}; requests no policy covers are not recorded.
 *
 * <p>All meters are created up front for the bounded tag space (3 algorithms × 4 results), so the
 * hot path only looks them up in arrays. Tags never include user, IP, path or policy.
 */
public class RateLimitTelemetry {

    /** Decisions are typically sub-millisecond; the default histogram range would hide them. */
    public static final Duration MIN_EXPECTED = Duration.ofNanos(50_000);
    public static final Duration MAX_EXPECTED = Duration.ofSeconds(1);

    private final Map<AlgorithmType, Map<RateLimitOutcome, Counter>> requests = new EnumMap<>(AlgorithmType.class);
    private final Map<AlgorithmType, Map<RateLimitOutcome, Timer>> durations = new EnumMap<>(AlgorithmType.class);
    private final Map<AlgorithmType, Counter> allowed = new EnumMap<>(AlgorithmType.class);
    private final Map<AlgorithmType, Counter> rejected = new EnumMap<>(AlgorithmType.class);
    private final PolicyStatsCollector stats;

    public RateLimitTelemetry(MeterRegistry meters, PolicyStatsCollector stats) {
        this.stats = stats;
        for (AlgorithmType algorithm : AlgorithmType.values()) {
            String alg = algorithmTag(algorithm);
            Map<RateLimitOutcome, Counter> byResult = new EnumMap<>(RateLimitOutcome.class);
            Map<RateLimitOutcome, Timer> timers = new EnumMap<>(RateLimitOutcome.class);
            for (RateLimitOutcome outcome : RateLimitOutcome.values()) {
                byResult.put(outcome, Counter.builder("ratelimiter.requests").tag("algorithm", alg).tag("result", outcome.tag())
                        .description("Rate-limit decisions").register(meters));
                timers.put(outcome, Timer.builder("ratelimiter.request.duration").tag("algorithm", alg).tag("result", outcome.tag())
                        .description("Rate-limiter overhead per request: identity, policy lookup, algorithm, Redis round trip")
                        .publishPercentileHistogram()
                        .minimumExpectedValue(MIN_EXPECTED)
                        .maximumExpectedValue(MAX_EXPECTED)
                        .register(meters));
            }
            requests.put(algorithm, byResult);
            durations.put(algorithm, timers);
            allowed.put(algorithm, Counter.builder("ratelimiter.allowed").tag("algorithm", alg)
                    .description("Allowed rate-limit decisions").register(meters));
            rejected.put(algorithm, Counter.builder("ratelimiter.rejected").tag("algorithm", alg)
                    .description("Rejected (429) rate-limit decisions").register(meters));
        }
    }

    public static String algorithmTag(AlgorithmType algorithm) {
        return algorithm.name().toLowerCase(Locale.ROOT);
    }

    public void record(ResolvedPolicy policy, RateLimitOutcome outcome, long durationNanos) {
        AlgorithmType algorithm = policy.rule().algorithm();
        requests.get(algorithm).get(outcome).increment();
        durations.get(algorithm).get(outcome).record(durationNanos, TimeUnit.NANOSECONDS);
        if (outcome == RateLimitOutcome.ALLOWED) {
            allowed.get(algorithm).increment();
        } else if (outcome == RateLimitOutcome.REJECTED) {
            rejected.get(algorithm).increment();
        }
        if (stats != null) {
            stats.record(policy.id(), outcome);
        }
    }
}
