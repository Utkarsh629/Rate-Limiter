package com.utkarsh.ratelimiter.algorithm;

/**
 * Strategy interface for rate-limiting algorithms.
 *
 * <p>Implementations must make each call a single atomic check-and-update of the shared state,
 * so that any number of concurrent callers, on any number of application instances, cannot
 * together exceed the rule. They must not depend on HTTP, policies storage, or local counters.
 */
public interface RateLimitAlgorithm {

    AlgorithmType type();

    /**
     * Attempts to consume one unit of quota for {@code key} under {@code rule}.
     *
     * @throws IllegalArgumentException if {@code rule} is for a different algorithm
     * @throws com.utkarsh.ratelimiter.redis.RateLimitStoreException if the state store fails
     */
    RateLimitResult tryAcquire(RateLimitKey key, RateLimitRule rule);
}
