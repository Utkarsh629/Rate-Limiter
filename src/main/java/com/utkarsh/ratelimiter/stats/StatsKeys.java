package com.utkarsh.ratelimiter.stats;

/** {@code <prefix>:stats:{<policyId>}:<instanceId>} and {@code <prefix>:stats:{<policyId>}:instances}. */
public record StatsKeys(String prefix) {

    public String shard(long policyId, String instanceId) {
        return prefix + ":stats:{" + policyId + "}:" + instanceId;
    }

    public String index(long policyId) {
        return prefix + ":stats:{" + policyId + "}:instances";
    }
}
