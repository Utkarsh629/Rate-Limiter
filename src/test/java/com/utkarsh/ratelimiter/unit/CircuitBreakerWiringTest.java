package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.utkarsh.ratelimiter.config.RateLimitAlgorithmConfiguration;
import com.utkarsh.ratelimiter.config.RateLimiterProperties;
import com.utkarsh.ratelimiter.redis.CircuitBreakingScriptExecutor;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.RedisTemplateScriptExecutor;
import com.utkarsh.ratelimiter.resilience.CircuitBreakerConfiguration;
import com.utkarsh.ratelimiter.resilience.CircuitBreakerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The breaker sits at the executor boundary when enabled, is absent when disabled, and settings are validated. */
class CircuitBreakerWiringTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({RateLimiterProperties.class, CircuitBreakerProperties.class})
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class, CircuitBreakerConfiguration.class, RateLimitAlgorithmConfiguration.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("ratelimiter.instance-id=wiring-test");

    @Test
    void enabledByDefaultWrapsTheRedisExecutor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitScriptExecutor.class)).isInstanceOf(CircuitBreakingScriptExecutor.class);
            CircuitBreakerProperties p = context.getBean(CircuitBreakerProperties.class);
            assertThat(p.slidingWindowSize()).isEqualTo(20);
            assertThat(p.minimumNumberOfCalls()).isEqualTo(10);
            assertThat(p.failureRateThreshold()).isEqualTo(50);
            assertThat(p.waitDurationInOpenState()).hasSeconds(5);
            assertThat(p.permittedCallsInHalfOpen()).isEqualTo(3);
            assertThat(p.maxWaitInHalfOpen()).hasSeconds(2);
        });
    }

    @Test
    void disabledMeansDirectRedisCalls() {
        runner.withPropertyValues("ratelimiter.circuit-breaker.enabled=false").run(context ->
                assertThat(context.getBean(RateLimitScriptExecutor.class)).isInstanceOf(RedisTemplateScriptExecutor.class));
    }

    @Test
    void rejectsInconsistentSettings() {
        runner.withPropertyValues("ratelimiter.circuit-breaker.minimum-number-of-calls=30")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ratelimiter.circuit-breaker.wait-duration-in-open-state=2s")
                .run(context -> assertThat(context).hasFailed()); // must exceed the 2 s probe timeout
        runner.withPropertyValues("ratelimiter.circuit-breaker.failure-rate-threshold=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
