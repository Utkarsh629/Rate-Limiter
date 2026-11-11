package com.utkarsh.ratelimiter.policy;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness contributor {@code policies}: UP once a policy snapshot exists (from the database, or
 * from development fallbacks); DOWN before that. Details show the source and staleness.
 */
public class PolicyHealthIndicator implements HealthIndicator {

    private final PolicyCache cache;

    public PolicyHealthIndicator(PolicyCache cache) {
        this.cache = cache;
    }

    @Override
    public Health health() {
        PolicySnapshot snapshot = cache.snapshot();
        Health.Builder builder = cache.isInitialized() ? Health.up() : Health.down();
        builder.withDetail("source", snapshot.source())
                .withDetail("policies", snapshot.policies().size())
                .withDetail("successfulReloads", cache.successfulReloads())
                .withDetail("failedReloads", cache.failedReloads());
        if (snapshot.loadedAt() != null) {
            builder.withDetail("loadedAt", snapshot.loadedAt().toString());
        }
        if (snapshot.skipped() > 0) {
            builder.withDetail("skippedInvalidPolicies", snapshot.skipped());
        }
        if (cache.lastError() != null) {
            builder.withDetail("lastError", cache.lastError());
        }
        return builder.build();
    }
}
