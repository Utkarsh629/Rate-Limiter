package com.utkarsh.ratelimiter.resilience;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Circuit breaker around Redis script calls (design: docs/proposals/2D-redis-circuit-breaker.md).
 *
 * @param slidingWindowSize         count-based window of recorded calls
 * @param minimumNumberOfCalls      no evaluation (so no opening) before this many recorded calls
 * @param failureRateThreshold      percent of infrastructure failures in the window that opens the breaker
 * @param waitDurationInOpenState   OPEN time before the next request starts probing
 * @param permittedCallsInHalfOpen  probes per half-open cycle; ALL must succeed to close
 * @param maxWaitInHalfOpen         maximum time ONE admitted probe may stay unresolved; a probe still
 *                                  unresolved after this reopens the breaker. Quiet traffic (fewer probes
 *                                  arriving) does NOT reopen it (decision R5, option B)
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter.circuit-breaker")
public record CircuitBreakerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") @Min(1) @Max(10_000) int slidingWindowSize,
        @DefaultValue("10") @Min(1) @Max(10_000) int minimumNumberOfCalls,
        @DefaultValue("50") @Min(1) @Max(100) int failureRateThreshold,
        @DefaultValue("5s") @NotNull @DurationMin(millis = 100) @DurationMax(minutes = 10) Duration waitDurationInOpenState,
        @DefaultValue("3") @Min(1) @Max(100) int permittedCallsInHalfOpen,
        @DefaultValue("2s") @NotNull @DurationMin(millis = 100) @DurationMax(minutes = 5) Duration maxWaitInHalfOpen) {

    @AssertTrue(message = "minimum-number-of-calls must not exceed sliding-window-size")
    public boolean isMinimumCallsWithinWindow() {
        return minimumNumberOfCalls <= slidingWindowSize;
    }

    /**
     * Results older than {@code maxWaitInHalfOpen} are never recorded, so a recorded result always lands in
     * the half-open cycle that admitted it or in OPEN, never in a later cycle, provided the open wait is longer.
     */
    @AssertTrue(message = "wait-duration-in-open-state must be longer than max-wait-in-half-open")
    public boolean isOpenWaitLongerThanProbeTimeout() {
        return waitDurationInOpenState == null || maxWaitInHalfOpen == null
                || waitDurationInOpenState.compareTo(maxWaitInHalfOpen) > 0;
    }
}
