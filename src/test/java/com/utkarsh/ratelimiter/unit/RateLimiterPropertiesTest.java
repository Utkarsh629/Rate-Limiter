package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.config.RateLimiterProperties;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Binding and validation of {@code ratelimiter.*}: bad configuration must stop startup. */
class RateLimiterPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimiterProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void appliesSafeDefaults() {
        runner.withPropertyValues("ratelimiter.instance-id=app-1").run(context -> {
            RateLimiterProperties props = context.getBean(RateLimiterProperties.class);

            assertThat(props.instanceId()).isEqualTo("app-1");
            assertThat(props.keyPrefix()).isEqualTo("rl");
            assertThat(props.defaultFailureMode()).isEqualTo(FailureMode.FAIL_OPEN);
            assertThat(props.policy().reloadInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.policy().fallbackEnabled()).isFalse();
            assertThat(props.stats().flushInterval()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void bindsOverrides() {
        runner.withPropertyValues(
                "ratelimiter.instance-id=app-2",
                "ratelimiter.default-failure-mode=FAIL_CLOSED",
                "ratelimiter.policy.fallback-enabled=true",
                "ratelimiter.stats.flush-interval=10s").run(context -> {
            RateLimiterProperties props = context.getBean(RateLimiterProperties.class);

            assertThat(props.defaultFailureMode()).isEqualTo(FailureMode.FAIL_CLOSED);
            assertThat(props.policy().fallbackEnabled()).isTrue();
            assertThat(props.stats().flushInterval()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void rejectsMissingInstanceId() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInstanceIdThatWouldBreakRedisKeys() {
        runner.withPropertyValues("ratelimiter.instance-id=app 1:{x}")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsSubSecondStatsFlushInterval() {
        runner.withPropertyValues("ratelimiter.instance-id=app-1", "ratelimiter.stats.flush-interval=100ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnknownFailureMode() {
        runner.withPropertyValues("ratelimiter.instance-id=app-1", "ratelimiter.default-failure-mode=MAYBE")
                .run(context -> assertThat(context).hasFailed());
    }
}
