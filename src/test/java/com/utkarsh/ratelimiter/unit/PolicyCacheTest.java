package com.utkarsh.ratelimiter.unit;

import static com.utkarsh.ratelimiter.unit.PolicyFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.utkarsh.ratelimiter.config.RateLimiterProperties;
import com.utkarsh.ratelimiter.policy.PolicyCache;
import com.utkarsh.ratelimiter.policy.PolicySnapshot;
import com.utkarsh.ratelimiter.policy.PolicySource;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PolicyCacheTest {

    private final PolicySource source = mock(PolicySource.class);
    private final List<ResolvedPolicy> fallbacks = List.of(policy(1_000_000_001L, null, "/api/**", null, 0));
    private PolicyCache cache;

    private PolicyCache cache(boolean fallbackEnabled, Duration reloadInterval) {
        RateLimiterProperties properties = new RateLimiterProperties("test", "rl", FailureMode.FAIL_OPEN,
                new RateLimiterProperties.Policy(reloadInterval, fallbackEnabled, List.of()),
                new RateLimiterProperties.Stats(Duration.ofSeconds(5)));
        cache = new PolicyCache(source, properties, fallbacks, Clock.systemUTC());
        return cache;
    }

    @AfterEach
    void stop() {
        if (cache != null) {
            cache.stop();
        }
    }

    private static PolicySource.Loaded loaded(ResolvedPolicy... policies) {
        return new PolicySource.Loaded(List.of(policies), 0);
    }

    @Test
    void startupLoadsFromTheDatabase() {
        when(source.load()).thenReturn(loaded(policy(1, null, "/api/**", null, 0)));
        PolicyCache cache = cache(false, Duration.ofMinutes(10));

        cache.start();

        assertThat(cache.isInitialized()).isTrue();
        assertThat(cache.snapshot().source()).isEqualTo(PolicySnapshot.Source.DATABASE);
        assertThat(cache.snapshot().policies()).extracting(ResolvedPolicy::id).containsExactly(1L);
    }

    @Test
    void productionStaysUninitialisedWhenTheDatabaseIsDownAtStartup() {
        when(source.load()).thenThrow(new DataAccessResourceFailureException("connection refused"));
        PolicyCache cache = cache(false, Duration.ofMinutes(10));

        cache.start();

        assertThat(cache.isInitialized()).isFalse();
        assertThat(cache.snapshot().policies()).isEmpty();
        assertThat(cache.lastError()).contains("connection refused");
    }

    @Test
    void developmentServesFallbacksUntilTheDatabaseRecovers() {
        when(source.load())
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenReturn(loaded(policy(1, null, "/api/**", null, 0)));
        PolicyCache cache = cache(true, Duration.ofMinutes(10));

        cache.start();
        assertThat(cache.snapshot().source()).isEqualTo(PolicySnapshot.Source.FALLBACK);
        assertThat(cache.snapshot().policies()).extracting(ResolvedPolicy::id).containsExactly(1_000_000_001L);

        assertThat(cache.reload("retry")).isTrue();
        assertThat(cache.snapshot().source()).isEqualTo(PolicySnapshot.Source.DATABASE);
        assertThat(cache.snapshot().policies()).extracting(ResolvedPolicy::id).containsExactly(1L);
    }

    @Test
    void failedReloadKeepsTheLastGoodDatabaseSnapshotEvenWithFallbackEnabled() {
        when(source.load())
                .thenReturn(loaded(policy(1, null, "/api/**", null, 0)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        PolicyCache cache = cache(true, Duration.ofMinutes(10));

        cache.start();
        assertThat(cache.reload("periodic")).isFalse();

        assertThat(cache.snapshot().source()).isEqualTo(PolicySnapshot.Source.DATABASE);
        assertThat(cache.snapshot().policies()).extracting(ResolvedPolicy::id).containsExactly(1L);
        assertThat(cache.failedReloads()).isEqualTo(1);
    }

    @Test
    void periodicReloadPicksUpChanges() {
        when(source.load())
                .thenReturn(loaded(policy(1, null, "/api/**", null, 0)))
                .thenReturn(loaded(policy(1, null, "/api/**", null, 0), policy(2, null, "/b/**", null, 0)));
        PolicyCache cache = cache(false, Duration.ofSeconds(1));

        cache.start();

        await().atMost(Duration.ofSeconds(3)).until(() -> cache.snapshot().policies().size() == 2);
    }

    @Test
    void concurrentReloadRequestsAreCoalesced() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(source.load()).thenReturn(loaded()).thenAnswer(invocation -> {
            release.await();
            return loaded();
        });
        PolicyCache cache = cache(false, Duration.ofMinutes(10));
        cache.start(); // load #1

        cache.requestReload("a"); // load #2, blocks on the latch
        await().until(() -> cache.lastAttemptAt() != null);
        for (int i = 0; i < 50; i++) {
            cache.requestReload("burst"); // at most one more queued
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(source, atMost(3)).load());
        Thread.sleep(200);
        verify(source, atMost(3)).load();
    }
}
