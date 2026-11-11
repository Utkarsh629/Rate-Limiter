package com.utkarsh.ratelimiter.algorithm;

import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import com.utkarsh.ratelimiter.redis.RateLimitScript;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.ScriptClock;
import java.util.List;

/**
 * Token bucket: up to {@code limit} tokens, refilled continuously at
 * {@code refillTokens / windowMillis}. Each request takes one token. Allows bursts up to the
 * capacity, then a sustained rate equal to the refill rate. See {@code scripts/token_bucket.lua}.
 */
public final class TokenBucketAlgorithm extends AbstractLuaRateLimitAlgorithm {

    public TokenBucketAlgorithm(RateLimitScriptExecutor executor, RateLimitKeyFactory keyFactory, ScriptClock clock) {
        super(executor, keyFactory, clock);
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.TOKEN_BUCKET;
    }

    @Override
    protected RateLimitScript script() {
        return RateLimitScript.TOKEN_BUCKET;
    }

    @Override
    protected List<String> ruleArguments(RateLimitRule rule) {
        return List.of(Long.toString(rule.limit()), Long.toString(rule.refillTokens()), Long.toString(rule.windowMillis()));
    }
}
