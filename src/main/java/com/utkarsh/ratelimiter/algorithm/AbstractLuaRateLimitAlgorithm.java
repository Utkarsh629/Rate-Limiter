package com.utkarsh.ratelimiter.algorithm;

import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import com.utkarsh.ratelimiter.redis.RateLimitScript;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.redis.ScriptClock;
import java.util.List;
import java.util.Objects;

/**
 * Shared skeleton for the Redis-backed algorithms: one script call per check, against one key.
 *
 * <p>Every script returns the same shape, {@code {allowed, remaining, retryAfterMs, resetAfterMs}},
 * and takes the clock argument last. Subclasses only provide the script and its limit arguments.
 */
public abstract class AbstractLuaRateLimitAlgorithm implements RateLimitAlgorithm {

    private final RateLimitScriptExecutor executor;
    private final RateLimitKeyFactory keyFactory;
    private final ScriptClock clock;

    protected AbstractLuaRateLimitAlgorithm(RateLimitScriptExecutor executor, RateLimitKeyFactory keyFactory, ScriptClock clock) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    protected abstract RateLimitScript script();

    /** Rule-specific arguments, in the order the script reads them (before the clock argument). */
    protected abstract List<String> ruleArguments(RateLimitRule rule);

    @Override
    public final RateLimitResult tryAcquire(RateLimitKey key, RateLimitRule rule) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(rule, "rule");
        if (rule.algorithm() != type()) {
            throw new IllegalArgumentException(type() + " cannot evaluate a " + rule.algorithm() + " rule");
        }
        List<String> args = new java.util.ArrayList<>(ruleArguments(rule));
        args.add(clock.timeArgument());

        List<Long> reply = executor.execute(script(), keyFactory.stateKey(key, type()), args);
        return toResult(reply, rule);
    }

    private RateLimitResult toResult(List<Long> reply, RateLimitRule rule) {
        if (reply.size() != 4) {
            throw new RateLimitStoreException(script() + " returned " + reply.size() + " values, expected 4");
        }
        try {
            return new RateLimitResult(reply.get(0) == 1L, rule.limit(), reply.get(1), reply.get(2), reply.get(3));
        } catch (IllegalArgumentException e) {
            throw new RateLimitStoreException(script() + " returned an inconsistent result " + reply, e);
        }
    }
}
