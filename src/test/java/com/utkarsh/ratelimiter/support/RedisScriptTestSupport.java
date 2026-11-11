package com.utkarsh.ratelimiter.support;

import com.utkarsh.ratelimiter.algorithm.FixedWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import com.utkarsh.ratelimiter.redis.RedisTemplateScriptExecutor;
import com.utkarsh.ratelimiter.redis.ScriptClock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Base class for script-level tests: a real Redis 7.4 container (started once per JVM and shared),
 * no Spring context. Each test starts from an empty database.
 */
public abstract class RedisScriptTestSupport {

    protected static final String PREFIX = "rl";
    /** 2027-01-15T08:00:00Z: a multiple of every window used in tests, so window starts are predictable. */
    protected static final long T0 = 1_800_000_000_000L;

    protected static final GenericContainer<?> REDIS;
    protected static final StringRedisTemplate redis;

    static {
        REDIS = new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE).withExposedPorts(6379);
        REDIS.start();
        redis = new StringRedisTemplate(newConnectionFactory());
    }

    protected final RateLimitKeyFactory keyFactory = new RateLimitKeyFactory(PREFIX);

    @BeforeEach
    void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    /** A separate client connection, as a separate application instance would have. */
    protected static LettuceConnectionFactory newConnectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    protected TokenBucketAlgorithm tokenBucket(ScriptClock clock) {
        return new TokenBucketAlgorithm(new RedisTemplateScriptExecutor(redis), keyFactory, clock);
    }

    protected FixedWindowAlgorithm fixedWindow(ScriptClock clock) {
        return new FixedWindowAlgorithm(new RedisTemplateScriptExecutor(redis), keyFactory, clock);
    }

    protected SlidingWindowAlgorithm slidingWindow(ScriptClock clock) {
        return new SlidingWindowAlgorithm(new RedisTemplateScriptExecutor(redis), keyFactory, clock);
    }

    protected static Map<Object, Object> hash(String key) {
        return redis.opsForHash().entries(key);
    }

    protected static long pttl(String key) {
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ttl == null ? -2 : ttl;
    }
}
