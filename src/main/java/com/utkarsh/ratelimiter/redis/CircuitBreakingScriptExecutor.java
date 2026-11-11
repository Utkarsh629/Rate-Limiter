package com.utkarsh.ratelimiter.redis;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit breaker at the Redis script-executor boundary (design: docs/proposals/2D-redis-circuit-breaker.md).
 *
 * <p>Resilience4j provides the lock-free state machine and the permits. This class adds the project's rules:
 * <ol>
 *   <li><b>Only infrastructure failures count</b> ({@link RedisFailureClassifier}); other errors release
 *       their permit and are never recorded.</li>
 *   <li><b>Three of three probes to close.</b> A probe (a call admitted in HALF_OPEN) that fails forces OPEN
 *       <em>before</em> its failure is reported. The library therefore never sees a completed half-open
 *       cycle that contains a failure, whatever order concurrent probes finish in, and successes reported
 *       after the forced transition land in OPEN, where they change nothing.</li>
 *   <li><b>The probe timeout bounds one in-flight probe</b> (decision R5, option B). A probe unresolved for
 *       longer than {@code maxWaitInHalfOpen} forces OPEN, detected either by any later caller while HALF_OPEN
 *       or by the probe itself on completion. Quiet traffic never reopens the breaker: the library waits
 *       (its own half-open timeout is disabled) until all permits have been used.</li>
 *   <li><b>No stale results.</b> A result that took longer than {@code maxWaitInHalfOpen} is never recorded.
 *       Since the open wait is validated to be longer, every recorded result lands in the half-open cycle
 *       that admitted it or in OPEN, never in a later cycle.</li>
 * </ol>
 * A refused call throws {@link RateLimitStoreUnavailableException}; the filter maps it to the policy's
 * failure mode exactly like a Redis error.
 */
public final class CircuitBreakingScriptExecutor implements RateLimitScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingScriptExecutor.class);

    private final RateLimitScriptExecutor delegate;
    private final CircuitBreaker breaker;
    private final long probeTimeoutNanos;
    private final LongSupplier nanoTime;

    /** In-flight probes of the current half-open cycle: token -> admission time. Removal decides ownership. */
    private final Map<Object, Long> inFlightProbes = new ConcurrentHashMap<>();

    private final Map<RedisFailureClassifier.Type, Counter> errors = new EnumMap<>(RedisFailureClassifier.Type.class);
    private final Counter notPermitted;
    private final Counter ignored;

    public CircuitBreakingScriptExecutor(RateLimitScriptExecutor delegate, CircuitBreaker breaker, Duration probeTimeout,
                                         MeterRegistry meters, LongSupplier nanoTime) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.probeTimeoutNanos = probeTimeout.toNanos();
        this.nanoTime = nanoTime;
        for (RedisFailureClassifier.Type type : RedisFailureClassifier.Type.values()) {
            errors.put(type, Counter.builder("ratelimiter.redis.errors").tag("type", type.tag())
                    .description("Redis infrastructure failures and circuit-breaker refusals").register(meters));
        }
        this.notPermitted = Counter.builder("ratelimiter.redis.errors").tag("type", "circuit_open")
                .description("Redis infrastructure failures and circuit-breaker refusals").register(meters);
        this.ignored = Counter.builder("ratelimiter.redis.ignored.errors")
                .description("Errors from Redis calls that are not infrastructure failures (do not trip the breaker)")
                .register(meters);
    }

    @Override
    public List<Long> execute(RateLimitScript script, String key, List<String> args) {
        reopenIfAProbeIsHung();
        if (!breaker.tryAcquirePermission()) {
            notPermitted.increment();
            throw new RateLimitStoreUnavailableException("Redis circuit breaker is " + breaker.getState() + "; Redis not called");
        }
        boolean probe = breaker.getState() == CircuitBreaker.State.HALF_OPEN;
        Object token = probe ? new Object() : null;
        long start = nanoTime.getAsLong();
        if (probe) {
            inFlightProbes.put(token, start);
        }
        RuntimeException failure = null;
        try {
            return delegate.execute(script, key, args);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            complete(probe, token, start, failure);
        }
    }

    private void complete(boolean probe, Object token, long start, RuntimeException failure) {
        long elapsed = nanoTime.getAsLong() - start;
        Optional<RedisFailureClassifier.Type> infrastructure =
                failure == null ? Optional.empty() : RedisFailureClassifier.classify(failure);
        infrastructure.ifPresent(type -> errors.get(type).increment());
        if (failure != null && infrastructure.isEmpty()) {
            ignored.increment();
        }

        if (probe && inFlightProbes.remove(token) == null) {
            return; // already expired by the hung-probe guard, which reopened the breaker: never record it
        }
        if (elapsed > probeTimeoutNanos) {
            // Too old to trust (and may belong to an earlier cycle): never recorded. A probe this slow fails the cycle.
            if (probe) {
                forceOpen("probe unresolved for " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
            }
            return;
        }
        if (failure == null) {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
        } else if (infrastructure.isPresent()) {
            if (probe) {
                forceOpen("probe failed: " + failure.getMessage()); // before reporting: no partial-success close
            }
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, failure);
        } else {
            breaker.releasePermission(); // programming error: neither success nor failure
        }
    }

    /** Lock-free guard, run by every caller while HALF_OPEN: a probe in flight longer than the timeout reopens. */
    private void reopenIfAProbeIsHung() {
        if (inFlightProbes.isEmpty() || breaker.getState() != CircuitBreaker.State.HALF_OPEN) {
            return;
        }
        long now = nanoTime.getAsLong();
        for (Map.Entry<Object, Long> entry : inFlightProbes.entrySet()) {
            if (now - entry.getValue() > probeTimeoutNanos && inFlightProbes.remove(entry.getKey(), entry.getValue())) {
                forceOpen("probe unresolved for more than " + TimeUnit.NANOSECONDS.toMillis(probeTimeoutNanos) + " ms");
            }
        }
    }

    private void forceOpen(String reason) {
        if (breaker.getState() == CircuitBreaker.State.HALF_OPEN) {
            log.warn("Redis circuit breaker: half-open probe did not succeed ({}); reopening", reason);
            breaker.transitionToOpenState(); // no-op if another thread already reopened it
        }
    }
}
