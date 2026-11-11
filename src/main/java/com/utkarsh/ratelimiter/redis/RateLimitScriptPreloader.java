package com.utkarsh.ratelimiter.redis;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Loads the rate-limit scripts into Redis at startup, before the instance reports itself ready
 * (application runners complete before readiness switches to ACCEPTING_TRAFFIC).
 *
 * <p>Without this, the first request on a fresh instance pays for connection setup, client class
 * loading and an {@code EVALSHA → NOSCRIPT → EVAL} round trip, which can exceed the 50 ms command
 * timeout and make that request fail open (found by FailureModeIT). After preloading, the first
 * {@code EVALSHA} hits the script cache. Failure here is not fatal: if Redis is down at startup, the
 * scripts are loaded on first use, as before.
 */
public class RateLimitScriptPreloader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RateLimitScriptPreloader.class);

    private final StringRedisTemplate redis;

    public RateLimitScriptPreloader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (RateLimitScript script : RateLimitScript.values()) {
                byte[] body = script.redisScript().getScriptAsString().getBytes(StandardCharsets.UTF_8);
                String sha = redis.execute((RedisCallback<String>) connection -> connection.scriptingCommands().scriptLoad(body));
                if (!script.sha1().equals(sha)) {
                    throw new IllegalStateException("SHA mismatch for " + script + ": " + sha);
                }
            }
            log.info("Preloaded {} rate-limit scripts into Redis", RateLimitScript.values().length);
        } catch (RuntimeException e) {
            log.warn("Could not preload rate-limit scripts (they will be loaded on first use): {}", e.toString());
        }
    }
}
