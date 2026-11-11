package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitRuleTest {

    @Test
    void factoriesBuildValidRules() {
        assertThat(RateLimitRule.tokenBucket(100, 10, Duration.ofSeconds(1)))
                .isEqualTo(new RateLimitRule(AlgorithmType.TOKEN_BUCKET, 100, 1_000, 10));
        assertThat(RateLimitRule.fixedWindow(20, Duration.ofMinutes(1)))
                .isEqualTo(new RateLimitRule(AlgorithmType.FIXED_WINDOW, 20, 60_000, 20));
        assertThat(RateLimitRule.slidingWindow(20, Duration.ofMinutes(1)))
                .isEqualTo(new RateLimitRule(AlgorithmType.SLIDING_WINDOW, 20, 60_000, 20));
    }

    @Test
    void acceptsBoundaryValues() {
        RateLimitRule.fixedWindow(1, Duration.ofMillis(RateLimitRule.MIN_WINDOW_MILLIS));
        RateLimitRule.tokenBucket(RateLimitRule.MAX_LIMIT, RateLimitRule.MAX_LIMIT, Duration.ofHours(24));
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitRule.fixedWindow(0, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitRule.fixedWindow(RateLimitRule.MAX_LIMIT + 1, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitRule.fixedWindow(1, Duration.ofMillis(99)));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitRule.fixedWindow(1, Duration.ofHours(24).plusMillis(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitRule.tokenBucket(10, 0, Duration.ofSeconds(1)));
    }

    @Test
    void refillTokensOnlyAppliesToTokenBucket() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitRule(AlgorithmType.FIXED_WINDOW, 10, 1_000, 5))
                .withMessageContaining("TOKEN_BUCKET");
    }

    @Test
    void boundsKeepLuaArithmeticExact() {
        // Largest product in any script: limit x window (and refill x elapsed, which is bounded by it).
        long largest = RateLimitRule.MAX_LIMIT * RateLimitRule.MAX_WINDOW_MILLIS;
        assertThat(largest).isLessThan(1L << 53);
    }
}
