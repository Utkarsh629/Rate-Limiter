package com.utkarsh.ratelimiter.redis;

import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The three rate-limit Lua scripts, loaded once from {@code classpath:scripts/}. Spring Data Redis
 * runs them with {@code EVALSHA} and falls back to {@code EVAL} if Redis answers {@code NOSCRIPT}
 * (e.g. after a Redis restart), so the script body is sent over the network only when needed.
 */
public enum RateLimitScript {

    TOKEN_BUCKET("scripts/token_bucket.lua"),
    FIXED_WINDOW("scripts/fixed_window.lua"),
    SLIDING_WINDOW("scripts/sliding_window.lua");

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> redisScript;

    @SuppressWarnings("rawtypes")
    RateLimitScript(String location) {
        // The script text is read once, with this class's own class loader, and held as a string:
        // - a ClassPathResource without an explicit loader captures the thread context class loader of
        //   whichever thread first touches this enum (e.g. a web-app loader that is later stopped);
        // - a resource-backed DefaultRedisScript re-checks the file's lastModified inside a synchronized
        //   block on every call; a string-backed one does not.
        DefaultRedisScript<List> script = new DefaultRedisScript<>(ScriptText.load(location), List.class);
        this.redisScript = script;
    }

    @SuppressWarnings("rawtypes")
    public RedisScript<List> redisScript() {
        return redisScript;
    }

    /** SHA1 of the script body, as used by {@code EVALSHA}. */
    public String sha1() {
        return redisScript.getSha1();
    }
}
