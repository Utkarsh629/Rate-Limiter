package com.utkarsh.ratelimiter.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health contributor {@code rateLimitStore}: the Redis circuit breaker. UP when CLOSED, OUT_OF_SERVICE
 * when OPEN or HALF_OPEN. Part of the {@code dependencies} group, deliberately NOT of readiness (R1):
 * an open breaker means failure modes apply, not that the instance should stop receiving traffic.
 */
public class RateLimitStoreHealthIndicator implements HealthIndicator {

    private final CircuitBreaker breaker;

    public RateLimitStoreHealthIndicator(CircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public Health health() {
        CircuitBreaker.State state = breaker.getState();
        Health.Builder builder = state == CircuitBreaker.State.CLOSED ? Health.up() : Health.outOfService();
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        return builder
                .withDetail("circuitBreaker", state)
                .withDetail("failureRate", metrics.getFailureRate())
                .withDetail("bufferedCalls", metrics.getNumberOfBufferedCalls())
                .withDetail("notPermittedCalls", metrics.getNumberOfNotPermittedCalls())
                .build();
    }
}
