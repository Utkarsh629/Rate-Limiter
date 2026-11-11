package com.utkarsh.ratelimiter.config;

import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.algorithm.FixedWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitAlgorithm;
import com.utkarsh.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.utkarsh.ratelimiter.redis.CircuitBreakingScriptExecutor;
import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import com.utkarsh.ratelimiter.resilience.CircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.RateLimitScriptPreloader;
import com.utkarsh.ratelimiter.redis.RedisServerClock;
import com.utkarsh.ratelimiter.redis.RedisTemplateScriptExecutor;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the algorithm strategies. The clock is hard-wired to {@link RedisServerClock} rather than
 * injected as a bean, so no configuration or bean override can make production scripts use
 * anything but Redis server time.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitAlgorithmConfiguration {

    @Bean
    RateLimitKeyFactory rateLimitKeyFactory(RateLimiterProperties properties) {
        return new RateLimitKeyFactory(properties.keyPrefix());
    }

    /** The breaker boundary: algorithms call Redis only through this executor (2D). */
    @Bean
    RateLimitScriptExecutor rateLimitScriptExecutor(StringRedisTemplate redis, CircuitBreakerProperties breakerProperties,
                                                    CircuitBreaker rateLimitCircuitBreaker, MeterRegistry meters) {
        // The executor times its own Redis round trip (3A.1); the breaker wraps it, so refused calls are not timed.
        RateLimitScriptExecutor redisExecutor = new RedisTemplateScriptExecutor(redis, meters);
        if (!breakerProperties.enabled()) {
            return redisExecutor;
        }
        return new CircuitBreakingScriptExecutor(redisExecutor, rateLimitCircuitBreaker,
                breakerProperties.maxWaitInHalfOpen(), meters, System::nanoTime);
    }

    @Bean
    RateLimitScriptPreloader rateLimitScriptPreloader(StringRedisTemplate redis) {
        return new RateLimitScriptPreloader(redis);
    }

    @Bean
    TokenBucketAlgorithm tokenBucketAlgorithm(RateLimitScriptExecutor executor, RateLimitKeyFactory keys) {
        return new TokenBucketAlgorithm(executor, keys, RedisServerClock.INSTANCE);
    }

    @Bean
    FixedWindowAlgorithm fixedWindowAlgorithm(RateLimitScriptExecutor executor, RateLimitKeyFactory keys) {
        return new FixedWindowAlgorithm(executor, keys, RedisServerClock.INSTANCE);
    }

    @Bean
    SlidingWindowAlgorithm slidingWindowAlgorithm(RateLimitScriptExecutor executor, RateLimitKeyFactory keys) {
        return new SlidingWindowAlgorithm(executor, keys, RedisServerClock.INSTANCE);
    }

    @Bean
    AlgorithmRegistry algorithmRegistry(List<RateLimitAlgorithm> algorithms) {
        return new AlgorithmRegistry(algorithms);
    }
}
