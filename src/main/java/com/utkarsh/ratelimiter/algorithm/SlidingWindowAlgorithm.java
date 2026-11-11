package com.utkarsh.ratelimiter.algorithm;

import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import com.utkarsh.ratelimiter.redis.RateLimitScript;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.ScriptClock;
import java.util.List;

/**
 * Sliding window counter: estimates the request count over the last {@code W} ms as
 * {@code previous × (W − elapsed) / W + current} and admits a request only if the estimate stays
 * within {@code limit}. O(1) memory per client; approximate (see docs/05). See
 * {@code scripts/sliding_window.lua}.
 */
public final class SlidingWindowAlgorithm extends AbstractLuaRateLimitAlgorithm {

    public SlidingWindowAlgorithm(RateLimitScriptExecutor executor, RateLimitKeyFactory keyFactory, ScriptClock clock) {
        super(executor, keyFactory, clock);
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.SLIDING_WINDOW;
    }

    @Override
    protected RateLimitScript script() {
        return RateLimitScript.SLIDING_WINDOW;
    }

    @Override
    protected List<String> ruleArguments(RateLimitRule rule) {
        return List.of(Long.toString(rule.limit()), Long.toString(rule.windowMillis()));
    }
}
