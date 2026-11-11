package com.utkarsh.ratelimiter.config;

import com.utkarsh.ratelimiter.metrics.RateLimitTelemetry;
import com.utkarsh.ratelimiter.policy.PolicyRepository;
import com.utkarsh.ratelimiter.stats.PolicyStatsCollector;
import com.utkarsh.ratelimiter.stats.PolicyStatsService;
import com.utkarsh.ratelimiter.stats.RedisStatsSink;
import com.utkarsh.ratelimiter.stats.StatsKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Metrics recorder (3A.1) and per-instance statistics (3A.2). */
@Configuration(proxyBeanMethods = false)
public class TelemetryConfiguration {

    /** Shards expire this long after the last flush of their instance. */
    static final Duration STATS_TTL = Duration.ofHours(24);

    @Bean
    StatsKeys statsKeys(RateLimiterProperties properties) {
        return new StatsKeys(properties.keyPrefix());
    }

    @Bean
    PolicyStatsCollector policyStatsCollector(StringRedisTemplate redis, StatsKeys keys, RateLimiterProperties properties,
                                              MeterRegistry meters) {
        return new PolicyStatsCollector(new RedisStatsSink(redis, keys, properties.instanceId(), STATS_TTL),
                properties.stats().flushInterval(), meters);
    }

    @Bean
    PolicyStatsService policyStatsService(StringRedisTemplate redis, StatsKeys keys, PolicyRepository policies,
                                          RateLimiterProperties properties) {
        return new PolicyStatsService(redis, keys, policies, properties.stats().flushInterval());
    }

    @Bean
    RateLimitTelemetry rateLimitTelemetry(MeterRegistry meters, PolicyStatsCollector stats) {
        return new RateLimitTelemetry(meters, stats);
    }
}
