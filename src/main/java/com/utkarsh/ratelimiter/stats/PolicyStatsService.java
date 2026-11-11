package com.utkarsh.ratelimiter.stats;

import com.utkarsh.ratelimiter.policy.PolicyNotFoundException;
import com.utkarsh.ratelimiter.policy.PolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Reads and sums the per-instance shards of one policy. Admin path only: never on the request path. */
public class PolicyStatsService {

    private final StringRedisTemplate redis;
    private final StatsKeys keys;
    private final PolicyRepository policies;
    private final Duration flushInterval;

    public PolicyStatsService(StringRedisTemplate redis, StatsKeys keys, PolicyRepository policies, Duration flushInterval) {
        this.redis = redis;
        this.keys = keys;
        this.policies = policies;
        this.flushInterval = flushInterval;
    }

    public PolicyStatsResponse stats(long policyId) {
        if (!policies.existsById(policyId)) {
            throw new PolicyNotFoundException(policyId);
        }
        Set<String> members = redis.opsForSet().members(keys.index(policyId));
        List<PolicyStatsResponse.InstanceStats> instances = new ArrayList<>();
        long allowed = 0, rejected = 0, failOpen = 0, failClosed = 0;
        for (String instanceId : new TreeSet<>(members == null ? Set.of() : members)) {
            Map<Object, Object> shard = redis.opsForHash().entries(keys.shard(policyId, instanceId));
            if (shard.isEmpty()) {
                continue; // shard expired (instance gone for > TTL); index entry is stale
            }
            PolicyStatsResponse.InstanceStats s = new PolicyStatsResponse.InstanceStats(instanceId,
                    number(shard, "allowed"), number(shard, "rejected"), number(shard, "fail_open"), number(shard, "fail_closed"),
                    Instant.ofEpochMilli(number(shard, "last_flush_ms")));
            instances.add(s);
            allowed += s.allowed();
            rejected += s.rejected();
            failOpen += s.failOpen();
            failClosed += s.failClosed();
        }
        double rate = allowed + rejected == 0 ? 0.0 : (double) rejected / (allowed + rejected);
        return new PolicyStatsResponse(policyId, allowed, rejected, failOpen, failClosed, rate, instances,
                "eventual: each instance flushes every " + flushInterval.toSeconds() + " s; unflushed counts of a crashed instance are lost",
                Instant.now());
    }

    private static long number(Map<Object, Object> shard, String field) {
        Object value = shard.get(field);
        return value == null ? 0 : Long.parseLong(value.toString());
    }
}
