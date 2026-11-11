package com.utkarsh.ratelimiter.filter;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled             master switch for HTTP rate limiting
 * @param excludedPaths       Spring PathPatterns never rate limited (checked before policy resolution):
 *                            probes and metrics, API docs, token minting, and the error page
 * @param failClosedRetryAfter {@code Retry-After} sent with 503 when a FAIL_CLOSED policy cannot reach Redis
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter.filter")
public record RateLimitFilterProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue({"/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/api/v1/auth/**", "/error"})
        @NotNull List<String> excludedPaths,
        @DefaultValue("5s") @DurationMin(seconds = 1) @DurationMax(minutes = 5) Duration failClosedRetryAfter) {
}
