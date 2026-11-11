package com.utkarsh.ratelimiter.stats;

import java.util.Map;

/**
 * One flush: the increments since the previous batch, per policy. Identified by (bootId, seq) so a batch
 * that is retried after an ambiguous failure (e.g. a timeout whose command still ran) is applied once.
 *
 * @param deltas policyId -> {allowed, rejected, failOpen, failClosed}
 */
public record StatsBatch(String bootId, long seq, Map<Long, long[]> deltas) {
}
