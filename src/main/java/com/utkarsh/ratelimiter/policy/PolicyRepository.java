package com.utkarsh.ratelimiter.policy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<RateLimitPolicy, Long> {

    /** Used only by snapshot loading (startup, pub/sub invalidation, periodic reload). */
    List<RateLimitPolicy> findAllByEnabledTrue();
}
