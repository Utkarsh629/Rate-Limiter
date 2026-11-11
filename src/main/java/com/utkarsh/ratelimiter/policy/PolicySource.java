package com.utkarsh.ratelimiter.policy;

import java.util.List;

/** Loads the enabled policies, compiled. Implemented by the database; mocked in unit tests. */
public interface PolicySource {

    Loaded load();

    /** @param skipped rows that failed to compile and were left out */
    record Loaded(List<ResolvedPolicy> policies, int skipped) {
    }
}
