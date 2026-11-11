package com.utkarsh.ratelimiter.redis;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.metrics.RateLimitTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RateLimitScriptExecutor} backed by Spring Data Redis (Lettuce).
 *
 * <p>When constructed with a {@link MeterRegistry} (the application wiring, Phase 3A) it also records the
 * Redis script round trip ({@code ratelimiter_redis_duration_seconds{algorithm,outcome}}) and the number
 * of scripts sent ({@code ratelimiter_algorithm_executions_total{algorithm}}). It sits inside the circuit
 * breaker, so calls the breaker refuses are neither sent nor timed.
 */
public final class RedisTemplateScriptExecutor implements RateLimitScriptExecutor {

    private final StringRedisTemplate redis;
    private final Map<RateLimitScript, Timer> success;
    private final Map<RateLimitScript, Timer> error;
    private final Map<RateLimitScript, Counter> executions;

    public RedisTemplateScriptExecutor(StringRedisTemplate redis) {
        this(redis, null);
    }

    public RedisTemplateScriptExecutor(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        if (meters == null) {
            success = error = null;
            executions = null;
            return;
        }
        success = new EnumMap<>(RateLimitScript.class);
        error = new EnumMap<>(RateLimitScript.class);
        executions = new EnumMap<>(RateLimitScript.class);
        for (RateLimitScript script : RateLimitScript.values()) {
            String algorithm = RateLimitTelemetry.algorithmTag(AlgorithmType.valueOf(script.name()));
            success.put(script, timer(meters, algorithm, "success"));
            error.put(script, timer(meters, algorithm, "error"));
            executions.put(script, Counter.builder("ratelimiter.algorithm.executions").tag("algorithm", algorithm)
                    .description("Rate-limit script executions sent to Redis").register(meters));
        }
    }

    private static Timer timer(MeterRegistry meters, String algorithm, String outcome) {
        return Timer.builder("ratelimiter.redis.duration").tag("algorithm", algorithm).tag("outcome", outcome)
                .description("Redis script round trip as seen by the application")
                .publishPercentileHistogram()
                .minimumExpectedValue(RateLimitTelemetry.MIN_EXPECTED)
                .maximumExpectedValue(RateLimitTelemetry.MAX_EXPECTED)
                .register(meters);
    }

    @Override
    public List<Long> execute(RateLimitScript script, String key, List<String> args) {
        if (executions != null) {
            executions.get(script).increment();
        }
        long start = System.nanoTime();
        List<?> reply;
        try {
            reply = redis.execute(script.redisScript(), List.of(key), args.toArray());
        } catch (DataAccessException | PoolException e) {
            // PoolException (connection-pool exhaustion) is not a DataAccessException in Spring Data Redis.
            time(error, script, start);
            throw new RateLimitStoreException("Redis script " + script + " failed", e);
        }
        time(success, script, start);
        return toLongs(script, reply);
    }

    private static void time(Map<RateLimitScript, Timer> timers, RateLimitScript script, long start) {
        if (timers != null) {
            timers.get(script).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private static List<Long> toLongs(RateLimitScript script, List<?> reply) {
        if (reply == null) {
            throw new RateLimitStoreException("Redis script " + script + " returned no reply");
        }
        List<Long> values = new ArrayList<>(reply.size());
        for (Object element : reply) {
            if (!(element instanceof Number number)) {
                throw new RateLimitStoreException("Redis script " + script + " returned non-integer element: " + element);
            }
            values.add(number.longValue());
        }
        return values;
    }
}
