package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlgorithmRegistryTest {

    private static RateLimitAlgorithm stub(AlgorithmType type) {
        RateLimitAlgorithm algorithm = mock(RateLimitAlgorithm.class);
        when(algorithm.type()).thenReturn(type);
        return algorithm;
    }

    @Test
    void dispatchesToTheStrategyNamedByTheRule() {
        RateLimitAlgorithm tb = stub(AlgorithmType.TOKEN_BUCKET);
        RateLimitAlgorithm fw = stub(AlgorithmType.FIXED_WINDOW);
        RateLimitAlgorithm sw = stub(AlgorithmType.SLIDING_WINDOW);
        AlgorithmRegistry registry = new AlgorithmRegistry(List.of(tb, fw, sw));
        RateLimitKey key = RateLimitKey.user(1, "a");
        RateLimitRule rule = RateLimitRule.fixedWindow(5, Duration.ofSeconds(1));
        RateLimitResult expected = new RateLimitResult(true, 5, 4, 0, 1_000);
        when(fw.tryAcquire(key, rule)).thenReturn(expected);

        assertThat(registry.tryAcquire(key, rule)).isSameAs(expected);
        verify(fw).tryAcquire(key, rule);
        assertThat(registry.get(AlgorithmType.TOKEN_BUCKET)).isSameAs(tb);
    }

    @Test
    void failsFastWhenAnAlgorithmIsMissing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new AlgorithmRegistry(List.of(stub(AlgorithmType.TOKEN_BUCKET), stub(AlgorithmType.FIXED_WINDOW))))
                .withMessageContaining("SLIDING_WINDOW");
    }

    @Test
    void failsFastOnDuplicateImplementations() {
        assertThatIllegalStateException().isThrownBy(() -> new AlgorithmRegistry(List.of(
                stub(AlgorithmType.TOKEN_BUCKET), stub(AlgorithmType.TOKEN_BUCKET),
                stub(AlgorithmType.FIXED_WINDOW), stub(AlgorithmType.SLIDING_WINDOW))));
    }
}
