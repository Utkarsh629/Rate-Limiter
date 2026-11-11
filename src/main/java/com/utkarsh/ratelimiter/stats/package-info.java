/**
 * Per-policy request statistics for {@code GET /api/v1/policies/{id}/stats}: counted in memory on the
 * request path, flushed exactly once per batch to a per-instance Redis shard, summed on read.
 */
package com.utkarsh.ratelimiter.stats;
