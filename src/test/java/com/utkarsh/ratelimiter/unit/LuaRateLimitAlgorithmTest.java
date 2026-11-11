package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.utkarsh.ratelimiter.algorithm.FixedWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.utkarsh.ratelimiter.redis.RateLimitKeyFactory;
import com.utkarsh.ratelimiter.redis.RateLimitScript;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.redis.RedisServerClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Java side of the algorithms, with Redis mocked out: argument building and reply mapping. */
class LuaRateLimitAlgorithmTest {

    private final RateLimitScriptExecutor executor = mock(RateLimitScriptExecutor.class);
    private final RateLimitKeyFactory keys = new RateLimitKeyFactory("rl");
    private final RateLimitKey key = RateLimitKey.user(4, "dave");

    @Test
    void tokenBucketSendsCapacityRefillPeriodAndAnEmptyClockArgumentInProduction() {
        when(executor.execute(any(), anyString(), anyList())).thenReturn(List.of(1L, 9L, 0L, 6_000L));
        TokenBucketAlgorithm algorithm = new TokenBucketAlgorithm(executor, keys, RedisServerClock.INSTANCE);

        RateLimitResult result = algorithm.tryAcquire(key, RateLimitRule.tokenBucket(10, 5, Duration.ofSeconds(30)));

        verify(executor).execute(RateLimitScript.TOKEN_BUCKET, "rl:{4:u:dave}:tb", List.of("10", "5", "30000", ""));
        assertThat(result).isEqualTo(new RateLimitResult(true, 10, 9, 0, 6_000));
    }

    @Test
    void fixedWindowSendsLimitWindowAndClock() {
        when(executor.execute(any(), anyString(), anyList())).thenReturn(List.of(0L, 0L, 1_500L, 1_500L));
        FixedWindowAlgorithm algorithm = new FixedWindowAlgorithm(executor, keys, RedisServerClock.INSTANCE);

        RateLimitResult result = algorithm.tryAcquire(key, RateLimitRule.fixedWindow(3, Duration.ofSeconds(2)));

        verify(executor).execute(RateLimitScript.FIXED_WINDOW, "rl:{4:u:dave}:fw", List.of("3", "2000", ""));
        assertThat(result).isEqualTo(new RateLimitResult(false, 3, 0, 1_500, 1_500));
    }

    @Test
    void slidingWindowSendsLimitWindowAndClock() {
        when(executor.execute(any(), anyString(), anyList())).thenReturn(List.of(1L, 2L, 0L, 4_000L));
        SlidingWindowAlgorithm algorithm = new SlidingWindowAlgorithm(executor, keys, () -> "12345");

        algorithm.tryAcquire(key, RateLimitRule.slidingWindow(3, Duration.ofSeconds(2)));

        verify(executor).execute(eq(RateLimitScript.SLIDING_WINDOW), eq("rl:{4:u:dave}:sw"), eq(List.of("3", "2000", "12345")));
    }

    @Test
    void rejectsARuleForAnotherAlgorithmWithoutCallingRedis() {
        TokenBucketAlgorithm algorithm = new TokenBucketAlgorithm(executor, keys, RedisServerClock.INSTANCE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> algorithm.tryAcquire(key, RateLimitRule.fixedWindow(3, Duration.ofSeconds(2))));
        verifyNoInteractions(executor);
    }

    @Test
    void malformedRepliesBecomeStoreExceptions() {
        FixedWindowAlgorithm algorithm = new FixedWindowAlgorithm(executor, keys, RedisServerClock.INSTANCE);
        RateLimitRule rule = RateLimitRule.fixedWindow(3, Duration.ofSeconds(2));

        when(executor.execute(any(), anyString(), anyList())).thenReturn(List.of(1L, 2L));
        assertThatThrownBy(() -> algorithm.tryAcquire(key, rule)).isInstanceOf(RateLimitStoreException.class);

        when(executor.execute(any(), anyString(), anyList())).thenReturn(List.of(1L, 7L, 0L, 0L)); // remaining > limit
        assertThatThrownBy(() -> algorithm.tryAcquire(key, rule)).isInstanceOf(RateLimitStoreException.class);
    }

    @Test
    void storeFailuresPropagateUnchanged() {
        FixedWindowAlgorithm algorithm = new FixedWindowAlgorithm(executor, keys, RedisServerClock.INSTANCE);
        when(executor.execute(any(), anyString(), anyList())).thenThrow(new RateLimitStoreException("down"));

        assertThatThrownBy(() -> algorithm.tryAcquire(key, RateLimitRule.fixedWindow(3, Duration.ofSeconds(2))))
                .isInstanceOf(RateLimitStoreException.class).hasMessage("down");
    }
}
