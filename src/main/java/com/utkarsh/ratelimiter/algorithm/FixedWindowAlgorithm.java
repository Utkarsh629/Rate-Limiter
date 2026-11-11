package com.utkarsh.ratelimiter.algorithm;

import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import com.utkarsh.ratelimiter.redis.RateLimitScript;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.ScriptClock;
import java.util.List;

/**
 * Fixed window counter: at most {@code limit} requests per aligned window {@code [k·W, (k+1)·W)}.
 * Cheap and exact within a window, but allows up to 2 × limit across a window boundary. See
 * {@code scripts/fixed_window.lua}.
 */
public final class FixedWindowAlgorithm extends AbstractLuaRateLimitAlgorithm {

    public FixedWindowAlgorithm(RateLimitScriptExecutor executor, RateLimitKeyFactory keyFactory, ScriptClock clock) {
        super(executor, keyFactory, clock);
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.FIXED_WINDOW;
    }

    @Override
    protected RateLimitScript script() {
        return RateLimitScript.FIXED_WINDOW;
    }

    @Override
    protected List<String> ruleArguments(RateLimitRule rule) {
        return List.of(Long.toString(rule.limit()), Long.toString(rule.windowMillis()));
    }
}
