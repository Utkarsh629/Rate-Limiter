package com.utkarsh.ratelimiter.redis;

import java.util.List;

/**
 * Executes a rate-limit Lua script against exactly one key. Abstracts Redis away from the
 * algorithms so they can be unit-tested with a mock, and gives Phase 5/6 a single place to add
 * timing, circuit breaking and error metrics.
 */
public interface RateLimitScriptExecutor {

    /**
     * @return the script's integer reply
     * @throws RateLimitStoreException on any Redis failure
     */
    List<Long> execute(RateLimitScript script, String key, List<String> args);
}
