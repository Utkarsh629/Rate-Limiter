package com.utkarsh.ratelimiter.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param secret    base64-encoded HMAC-SHA256 key, at least 32 bytes. From {@code JWT_SECRET}; no default.
 * @param issuer    required {@code iss} claim
 * @param clockSkew tolerance when checking {@code exp} / {@code nbf}
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("distributed-rate-limiter") @NotBlank String issuer,
        @DefaultValue("30s") @DurationMax(minutes = 5) Duration clockSkew) {
}
