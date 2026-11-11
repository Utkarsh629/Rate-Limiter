package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.policy.InvalidPolicyException;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.PolicyConflictException;
import com.utkarsh.ratelimiter.policy.PolicyStore;
import com.utkarsh.ratelimiter.policy.RateLimitPolicy;
import com.utkarsh.ratelimiter.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database constraints as the last line of defence: entities that bypass API validation are rejected
 * by MySQL, and the vendor errors (3819 CHECK, 1062 duplicate) become domain exceptions.
 */
class PolicyConstraintIT extends ApiTestSupport {

    @Autowired PolicyStore store;
    @Autowired TransactionTemplate tx;

    private static RateLimitPolicy policy(String name, AlgorithmType algorithm, int limit, long windowMs, Integer refill) {
        RateLimitPolicy p = new RateLimitPolicy();
        p.setName(name);
        p.setEndpointPattern("/api/**");
        p.setKeyBy(KeyBy.IP);
        p.setAlgorithm(algorithm);
        p.setLimitValue(limit);
        p.setWindowMs(windowMs);
        p.setRefillTokens(refill);
        return p;
    }

    private void save(RateLimitPolicy p) {
        tx.executeWithoutResult(status -> store.save(p));
    }

    @Test
    void check3819OnLimitBecomesInvalidPolicy() {
        assertThatThrownBy(() -> save(policy("it-c1", AlgorithmType.FIXED_WINDOW, 0, 60_000, null)))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("chk_policy_limit")
                .extracting("field").isEqualTo("limit");
    }

    @Test
    void check3819OnWindowBecomesInvalidPolicy() {
        assertThatThrownBy(() -> save(policy("it-c2", AlgorithmType.FIXED_WINDOW, 5, 10, null)))
                .isInstanceOf(InvalidPolicyException.class).extracting("field").isEqualTo("windowMs");
    }

    @Test
    void check3819OnRefillBecomesInvalidPolicy() {
        assertThatThrownBy(() -> save(policy("it-c3", AlgorithmType.SLIDING_WINDOW, 5, 60_000, 5)))
                .isInstanceOf(InvalidPolicyException.class).extracting("field").isEqualTo("refillTokens");
    }

    @Test
    void duplicate1062BecomesConflict() {
        assertThatThrownBy(() -> save(policy("free-tier", AlgorithmType.FIXED_WINDOW, 5, 60_000, null)))
                .isInstanceOf(PolicyConflictException.class);
    }
}
