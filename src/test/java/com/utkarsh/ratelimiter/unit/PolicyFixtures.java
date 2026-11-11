package com.utkarsh.ratelimiter.unit;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;

final class PolicyFixtures {

    private PolicyFixtures() {
    }

    static ResolvedPolicy policy(long id, String method, String pattern, Tier tier, int priority) {
        return ResolvedPolicy.compile(id, "p" + id, method, pattern, tier, KeyBy.USER, AlgorithmType.FIXED_WINDOW,
                10, 60_000, null, null, priority, 0);
    }
}
