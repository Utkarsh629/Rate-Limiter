package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The algorithms as the application wires them: Spring beans, the configured key prefix, and Redis
 * server time (no test clock anywhere in this context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(InfrastructureContainers.class)
class AlgorithmWiringIT {

    @Autowired AlgorithmRegistry registry;
    @Autowired ApplicationContext context;
    @Autowired StringRedisTemplate redis;

    @ParameterizedTest
    @EnumSource(AlgorithmType.class)
    void registryResolvesOneProductionStrategyPerAlgorithm(AlgorithmType type) {
        Map<String, RateLimitAlgorithm> beans = context.getBeansOfType(RateLimitAlgorithm.class);
        assertThat(beans).hasSize(3);
        assertThat(registry.get(type).type()).isEqualTo(type);
        assertThat(beans.values()).contains(registry.get(type));
    }

    @ParameterizedTest
    @EnumSource(AlgorithmType.class)
    void enforcesTheLimitUsingRedisServerTime(AlgorithmType type) {
        RateLimitRule rule = switch (type) {
            case TOKEN_BUCKET -> RateLimitRule.tokenBucket(3, 1, Duration.ofHours(1));
            case FIXED_WINDOW -> RateLimitRule.fixedWindow(3, Duration.ofHours(1));
            case SLIDING_WINDOW -> RateLimitRule.slidingWindow(3, Duration.ofHours(1));
        };
        RateLimitKey key = RateLimitKey.ip(99, "198.51.100." + type.ordinal());

        List<RateLimitResult> results = List.of(
                registry.tryAcquire(key, rule), registry.tryAcquire(key, rule),
                registry.tryAcquire(key, rule), registry.tryAcquire(key, rule));

        assertThat(results).extracting(RateLimitResult::allowed).containsExactly(true, true, true, false);
        assertThat(results).extracting(RateLimitResult::remaining).containsExactly(2L, 1L, 0L, 0L);
        assertThat(results.get(3).retryAfterMillis()).isPositive();

        String redisKey = "rl:{99:ip:198.51.100." + type.ordinal() + "}:" + type.keySuffix();
        assertThat(redis.hasKey(redisKey)).isTrue();
        assertThat(redis.getExpire(redisKey)).isPositive(); // every state key has a TTL
    }
}
