package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.algorithm.RateLimitKey;

/** The policy that applies to a request, and the quota key it is counted against. */
public record RateLimitTarget(ResolvedPolicy policy, RateLimitKey key) {
}
