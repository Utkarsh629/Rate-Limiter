package com.utkarsh.ratelimiter.config;

import com.utkarsh.ratelimiter.policy.PolicyCache;
import com.utkarsh.ratelimiter.policy.PolicyHealthIndicator;
import com.utkarsh.ratelimiter.policy.PolicyInvalidation;
import com.utkarsh.ratelimiter.policy.PolicySource;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Policy snapshot cache, its invalidation channel, readiness, and startup migration handling. */
@Configuration(proxyBeanMethods = false)
public class PolicyConfiguration {

    @Bean
    DeferredFlywayMigration deferredFlywayMigration() {
        return new DeferredFlywayMigration();
    }

    @Bean
    PolicyCache policyCache(PolicySource source, RateLimiterProperties properties) {
        // Compiling here validates fallback policies at startup, even when they are never used.
        List<ResolvedPolicy> fallbacks = properties.policy().fallbackPolicies().stream()
                .map(RateLimiterProperties.FallbackPolicy::toResolved)
                .toList();
        return new PolicyCache(source, properties, fallbacks, Clock.systemUTC());
    }

    @Bean
    PolicyInvalidation policyInvalidation(PolicyCache cache, StringRedisTemplate redis, RateLimiterProperties properties) {
        return new PolicyInvalidation(cache, redis, properties);
    }

    @Bean
    RedisMessageListenerContainer policyEventsListenerContainer(RedisConnectionFactory connectionFactory,
            PolicyInvalidation invalidation, RateLimiterProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(invalidation, new ChannelTopic(PolicyInvalidation.channelName(properties)));
        return container;
    }

    /** Bean name determines the health contributor name: "policies". */
    @Bean
    PolicyHealthIndicator policiesHealthIndicator(PolicyCache cache) {
        return new PolicyHealthIndicator(cache);
    }
}
