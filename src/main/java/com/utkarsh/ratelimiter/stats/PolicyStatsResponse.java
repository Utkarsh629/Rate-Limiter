package com.utkarsh.ratelimiter.stats;

import java.time.Instant;
import java.util.List;

/**
 * Body of {@code GET /api/v1/policies/{id}/stats}.
 *
 * @param rejectionRate rejected / (allowed + rejected); fail-open and fail-closed decisions are excluded
 *                      because Redis did not decide them
 */
public record PolicyStatsResponse(
        long policyId,
        long allowed,
        long rejected,
        long failOpen,
        long failClosed,
        double rejectionRate,
        List<InstanceStats> instances,
        String consistency,
        Instant generatedAt) {

    public record InstanceStats(String instanceId, long allowed, long rejected, long failOpen, long failClosed, Instant lastFlushAt) {
    }
}
