package com.utkarsh.ratelimiter.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Redis circuit breaker (one per instance), its Micrometer meters, transition logging, and its
 * health contributor. Values come from {@link CircuitBreakerProperties}; see the 2D design for semantics.
 */
@Configuration(proxyBeanMethods = false)
public class CircuitBreakerConfiguration {

    public static final String BREAKER_NAME = "redis-rate-limit";
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerConfiguration.class);

    @Bean
    CircuitBreakerRegistry rateLimitCircuitBreakerRegistry(CircuitBreakerProperties properties, MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(breakerConfig(properties));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    /** The library configuration for the project's breaker; also used by unit tests. */
    public static CircuitBreakerConfig breakerConfig(CircuitBreakerProperties properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpen())
                // R5 option B: the library waits in HALF_OPEN until every permit is used. The project's own
                // per-probe timeout (CircuitBreakingScriptExecutor) replaces the library's cycle-wide one.
                .maxWaitDurationInHalfOpenState(Duration.ZERO)
                // Lazy OPEN -> HALF_OPEN on the first request after the wait: no timer thread.
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                // Slow-call detection off: the 50 ms command timeout already turns slow calls into failures.
                .slowCallRateThreshold(100)
                .slowCallDurationThreshold(Duration.ofDays(1))
                .build();
    }

    @Bean
    CircuitBreaker rateLimitCircuitBreaker(CircuitBreakerRegistry registry, MeterRegistry meters) {
        return instrument(registry.circuitBreaker(BREAKER_NAME), meters);
    }

    /** Transition counter + logs; also used by unit tests so they observe the same meters. */
    public static CircuitBreaker instrument(CircuitBreaker breaker, MeterRegistry meters) {
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State from = event.getStateTransition().getFromState();
            CircuitBreaker.State to = event.getStateTransition().getToState();
            Counter.builder("ratelimiter.circuit.breaker.transitions")
                    .tag("from", from.name().toLowerCase(Locale.ROOT))
                    .tag("to", to.name().toLowerCase(Locale.ROOT))
                    .description("Redis circuit-breaker state transitions")
                    .register(meters).increment();
            if (to == CircuitBreaker.State.OPEN) {
                log.warn("Redis circuit breaker {} -> OPEN (failure rate {}%); Redis is not called for the next {}",
                        from, breaker.getMetrics().getFailureRate(), breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1) + " ms");
            } else {
                log.info("Redis circuit breaker {} -> {}", from, to);
            }
        });
        return breaker;
    }

    /** Bean name determines the health contributor name: "rateLimitStore" (dependencies group only, R1). */
    @Bean
    RateLimitStoreHealthIndicator rateLimitStoreHealthIndicator(CircuitBreaker rateLimitCircuitBreaker) {
        return new RateLimitStoreHealthIndicator(rateLimitCircuitBreaker);
    }
}
