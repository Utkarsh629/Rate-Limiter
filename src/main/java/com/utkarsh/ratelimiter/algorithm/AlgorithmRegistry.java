package com.utkarsh.ratelimiter.algorithm;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Selects the {@link RateLimitAlgorithm} strategy for a rule. Fails at construction (application
 * startup) if any algorithm type has no implementation or more than one.
 */
public final class AlgorithmRegistry {

    private final Map<AlgorithmType, RateLimitAlgorithm> byType = new EnumMap<>(AlgorithmType.class);

    public AlgorithmRegistry(Collection<? extends RateLimitAlgorithm> algorithms) {
        for (RateLimitAlgorithm algorithm : algorithms) {
            RateLimitAlgorithm previous = byType.putIfAbsent(algorithm.type(), algorithm);
            if (previous != null) {
                throw new IllegalStateException("Duplicate implementation for " + algorithm.type() + ": "
                        + previous.getClass().getName() + " and " + algorithm.getClass().getName());
            }
        }
        for (AlgorithmType type : AlgorithmType.values()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("No implementation registered for " + type);
            }
        }
    }

    public RateLimitAlgorithm get(AlgorithmType type) {
        return byType.get(type);
    }

    /** Runs the check with the algorithm the rule names. */
    public RateLimitResult tryAcquire(RateLimitKey key, RateLimitRule rule) {
        return get(rule.algorithm()).tryAcquire(key, rule);
    }
}
