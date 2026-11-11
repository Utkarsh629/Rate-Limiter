package com.utkarsh.ratelimiter.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Makes {@code spring.data.redis.lettuce.pool.enabled=true} actually apply to script calls.
 *
 * <p>By default {@link LettuceConnectionFactory} sends every non-blocking command, including EVALSHA,
 * over one shared native connection, and uses the pool only for blocking commands and transactions.
 * With the shared connection left on, the pool setting had no effect on rate-limit calls. When pooling
 * is enabled, the shared connection is switched off so each script call borrows a pooled connection.
 * Whether that is faster is a Phase 3 benchmark question; default remains pooling OFF.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.data.redis.lettuce.pool", name = "enabled", havingValue = "true")
public class RedisPoolingConfiguration {

    @Bean
    static BeanPostProcessor dedicatedPooledRedisConnections() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LettuceConnectionFactory factory) {
                    factory.setShareNativeConnection(false);
                }
                return bean;
            }
        };
    }
}
