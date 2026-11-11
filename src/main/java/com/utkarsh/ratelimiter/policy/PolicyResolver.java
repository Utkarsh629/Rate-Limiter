package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.identity.ClientIdentity;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Picks the policy for a request from the in-memory snapshot and derives its quota key. Pure memory:
 * no database or Redis access. The result feeds
 * {@link com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry#tryAcquire}.
 */
@Component
public class PolicyResolver {

    private final PolicyCache cache;

    public PolicyResolver(PolicyCache cache) {
        this.cache = cache;
    }

    public Optional<RateLimitTarget> resolve(HttpMethod method, String path, ClientIdentity identity) {
        return cache.snapshot().resolve(method, path, identity.tier())
                .map(policy -> new RateLimitTarget(policy, keyFor(policy, identity)));
    }

    public static RateLimitKey keyFor(ResolvedPolicy policy, ClientIdentity identity) {
        if (policy.keyBy() == KeyBy.USER && identity.authenticated()) {
            return RateLimitKey.user(policy.id(), identity.userId());
        }
        return RateLimitKey.ip(policy.id(), identity.clientIp());
    }
}
