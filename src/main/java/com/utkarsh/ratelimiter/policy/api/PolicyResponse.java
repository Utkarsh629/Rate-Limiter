package com.utkarsh.ratelimiter.policy.api;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.RateLimitPolicy;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import java.time.Instant;

public record PolicyResponse(
        long id,
        String name,
        String description,
        String httpMethod,
        String endpointPattern,
        Tier tier,
        KeyBy keyBy,
        AlgorithmType algorithm,
        int limit,
        long windowMs,
        Integer refillTokens,
        FailureMode failureMode,
        int priority,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static PolicyResponse from(RateLimitPolicy p) {
        return new PolicyResponse(p.getId(), p.getName(), p.getDescription(), p.getHttpMethod(), p.getEndpointPattern(),
                p.getTier(), p.getKeyBy(), p.getAlgorithm(), p.getLimitValue(), p.getWindowMs(), p.getRefillTokens(),
                p.getFailureMode(), p.getPriority(), p.isEnabled(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
