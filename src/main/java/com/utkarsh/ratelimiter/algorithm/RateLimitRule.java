package com.utkarsh.ratelimiter.algorithm;

import java.time.Duration;
import java.util.Objects;

/**
 * The limits an algorithm enforces: an immutable value derived from a policy. Contains no Redis
 * or HTTP concepts.
 *
 * <p>Bounds are chosen so that every intermediate value in the Lua scripts stays below
 * 2<sup>53</sup>, the largest range in which Lua's double-precision numbers represent integers
 * exactly: {@code limit × windowMillis ≤ 10^6 × 8.64·10^7 ≈ 8.6·10^13}.
 *
 * @param algorithm    which algorithm enforces the rule
 * @param limit        token-bucket capacity, or maximum requests per window
 * @param windowMillis window length, or the token-bucket refill period
 * @param refillTokens tokens added per {@code windowMillis} (token bucket only; equals {@code limit}
 *                     for the other algorithms and is ignored by them)
 */
public record RateLimitRule(AlgorithmType algorithm, long limit, long windowMillis, long refillTokens) {

    public static final long MAX_LIMIT = 1_000_000;
    public static final long MIN_WINDOW_MILLIS = 100;
    public static final long MAX_WINDOW_MILLIS = Duration.ofHours(24).toMillis();

    public RateLimitRule {
        Objects.requireNonNull(algorithm, "algorithm");
        requireRange("limit", limit, 1, MAX_LIMIT);
        requireRange("windowMillis", windowMillis, MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS);
        requireRange("refillTokens", refillTokens, 1, MAX_LIMIT);
        if (algorithm != AlgorithmType.TOKEN_BUCKET && refillTokens != limit) {
            throw new IllegalArgumentException("refillTokens applies only to TOKEN_BUCKET");
        }
    }

    /** Bucket of {@code capacity} tokens, refilled continuously at {@code refillTokens} per {@code refillPeriod}. */
    public static RateLimitRule tokenBucket(long capacity, long refillTokens, Duration refillPeriod) {
        return new RateLimitRule(AlgorithmType.TOKEN_BUCKET, capacity, refillPeriod.toMillis(), refillTokens);
    }

    public static RateLimitRule fixedWindow(long limit, Duration window) {
        return new RateLimitRule(AlgorithmType.FIXED_WINDOW, limit, window.toMillis(), limit);
    }

    public static RateLimitRule slidingWindow(long limit, Duration window) {
        return new RateLimitRule(AlgorithmType.SLIDING_WINDOW, limit, window.toMillis(), limit);
    }

    private static void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]: " + value);
        }
    }
}
