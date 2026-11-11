package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import org.junit.jupiter.api.Test;

class RateLimitKeyFactoryTest {

    private final RateLimitKeyFactory factory = new RateLimitKeyFactory("rl");

    @Test
    void formatsKeysWithAClusterHashTagAndAlgorithmSuffix() {
        assertThat(factory.stateKey(RateLimitKey.user(7, "u123"), AlgorithmType.TOKEN_BUCKET)).isEqualTo("rl:{7:u:u123}:tb");
        assertThat(factory.stateKey(RateLimitKey.ip(7, "203.0.113.7"), AlgorithmType.FIXED_WINDOW)).isEqualTo("rl:{7:ip:203.0.113.7}:fw");
        assertThat(factory.stateKey(RateLimitKey.ip(9, "2001:db8::1"), AlgorithmType.SLIDING_WINDOW)).isEqualTo("rl:{9:ip:2001:db8::1}:sw");
    }

    @Test
    void sameQuotaDifferentAlgorithmGetsDifferentKey() {
        RateLimitKey key = RateLimitKey.user(1, "alice");
        assertThat(factory.stateKey(key, AlgorithmType.TOKEN_BUCKET))
                .isNotEqualTo(factory.stateKey(key, AlgorithmType.SLIDING_WINDOW));
    }

    @Test
    void hashesSubjectsThatCouldBreakTheHashTagOrAreLong() {
        String braces = factory.stateKey(RateLimitKey.user(1, "evil}{x"), AlgorithmType.TOKEN_BUCKET);
        String spaces = factory.stateKey(RateLimitKey.user(1, "a b"), AlgorithmType.TOKEN_BUCKET);
        String longId = factory.stateKey(RateLimitKey.user(1, "x".repeat(129)), AlgorithmType.TOKEN_BUCKET);

        assertThat(braces).matches("rl:\\{1:u:#[0-9a-f]{64}}:tb");
        assertThat(spaces).matches("rl:\\{1:u:#[0-9a-f]{64}}:tb");
        assertThat(longId).matches("rl:\\{1:u:#[0-9a-f]{64}}:tb");
        assertThat(braces).isNotEqualTo(spaces);
    }

    @Test
    void hashingIsDeterministic() {
        RateLimitKey key = RateLimitKey.user(1, "naïve user");
        assertThat(factory.stateKey(key, AlgorithmType.FIXED_WINDOW)).isEqualTo(factory.stateKey(key, AlgorithmType.FIXED_WINDOW));
    }

    @Test
    void rejectsInvalidPrefixAndKeys() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitKeyFactory("RL:"));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitKey.user(0, "a"));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitKey.user(1, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitKey.user(1, "x".repeat(1025)));
    }
}
