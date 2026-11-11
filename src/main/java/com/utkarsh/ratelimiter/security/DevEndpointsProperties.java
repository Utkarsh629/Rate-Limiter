package com.utkarsh.ratelimiter.security;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Development-only endpoints (demo token minting, identity echo). Off by default; enabled in the
 * {@code dev} profile. The controllers are additionally excluded whenever the {@code prod} profile
 * is active, so a stray property cannot expose them in production.
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter.dev-endpoints")
public record DevEndpointsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1h") @DurationMax(hours = 24) Duration maxTokenTtl) {
}
