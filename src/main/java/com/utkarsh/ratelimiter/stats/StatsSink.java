package com.utkarsh.ratelimiter.stats;

/** Applies a batch durably and idempotently; throws on failure (the batch is then retried unchanged). */
public interface StatsSink {

    void apply(StatsBatch batch);
}
