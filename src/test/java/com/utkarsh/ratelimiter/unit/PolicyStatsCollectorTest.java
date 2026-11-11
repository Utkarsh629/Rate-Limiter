package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.metrics.RateLimitOutcome;
import com.utkarsh.ratelimiter.stats.PolicyStatsCollector;
import com.utkarsh.ratelimiter.stats.StatsBatch;
import com.utkarsh.ratelimiter.stats.StatsSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Snapshot-and-flush: no lost and no double-counted increments, including failed and ambiguous flushes. */
class PolicyStatsCollectorTest {

    /** Behaves like stats_apply.lua: applies each (boot, seq) at most once per policy shard. */
    static final class IdempotentSink implements StatsSink {
        final Map<Long, long[]> totals = new HashMap<>();
        final Map<Long, String> lastBoot = new HashMap<>();
        final Map<Long, Long> lastSeq = new HashMap<>();
        final List<StatsBatch> calls = new ArrayList<>();
        volatile String mode = "ok"; // ok | fail-before | fail-after (applied, but the caller sees an error)

        @Override
        public synchronized void apply(StatsBatch batch) {
            calls.add(batch);
            String m = mode.equals("random")
                    ? List.of("ok", "fail-before", "fail-after").get(ThreadLocalRandom.current().nextInt(3)) : mode;
            if (m.equals("fail-before")) {
                throw new IllegalStateException("connection refused");
            }
            batch.deltas().forEach((policy, d) -> {
                boolean seen = batch.bootId().equals(lastBoot.get(policy)) && batch.seq() <= lastSeq.getOrDefault(policy, 0L);
                if (!seen) {
                    long[] t = totals.computeIfAbsent(policy, p -> new long[4]);
                    for (int i = 0; i < 4; i++) {
                        t[i] += d[i];
                    }
                    lastBoot.put(policy, batch.bootId());
                    lastSeq.put(policy, batch.seq());
                }
            });
            if (m.equals("fail-after")) {
                throw new IllegalStateException("timeout (but applied)");
            }
        }
    }

    private final IdempotentSink sink = new IdempotentSink();
    private final PolicyStatsCollector collector = new PolicyStatsCollector(sink, Duration.ofSeconds(5), new SimpleMeterRegistry());

    @Test
    void flushSendsOnlyTheIncrementsSinceTheLastBatch() {
        collector.record(1, RateLimitOutcome.ALLOWED);
        collector.record(1, RateLimitOutcome.REJECTED);
        collector.record(2, RateLimitOutcome.FAIL_OPEN);
        assertThat(collector.flush()).isTrue();
        assertThat(collector.flush()).isTrue();             // nothing new: no call
        collector.record(1, RateLimitOutcome.ALLOWED);
        assertThat(collector.flush()).isTrue();

        assertThat(sink.calls).hasSize(2);
        assertThat(sink.calls.get(1).deltas().get(1L)).containsExactly(1, 0, 0, 0);
        assertThat(sink.totals.get(1L)).containsExactly(2, 1, 0, 0);
        assertThat(sink.totals.get(2L)).containsExactly(0, 0, 1, 0);
    }

    @Test
    void aFailedBatchIsRetriedUnchangedAndNewCountsGoToTheNextBatch() {
        collector.record(1, RateLimitOutcome.ALLOWED);
        sink.mode = "fail-before";
        assertThat(collector.flush()).isFalse();
        collector.record(1, RateLimitOutcome.ALLOWED);       // arrives while the first batch is pending
        assertThat(collector.flush()).isFalse();
        sink.mode = "ok";
        assertThat(collector.flush()).isTrue();             // the pending batch, unchanged
        assertThat(collector.flush()).isTrue();             // then the new increment

        assertThat(sink.calls).extracting(StatsBatch::seq).containsExactly(1L, 1L, 1L, 2L);
        assertThat(sink.calls.get(2).deltas().get(1L)).containsExactly(1, 0, 0, 0);
        assertThat(sink.totals.get(1L)).containsExactly(2, 0, 0, 0);
    }

    @Test
    void anAmbiguousFailureIsNotDoubleCounted() {
        collector.record(1, RateLimitOutcome.REJECTED);
        sink.mode = "fail-after";                             // applied, but the client saw a timeout
        assertThat(collector.flush()).isFalse();
        sink.mode = "ok";
        assertThat(collector.flush()).isTrue();             // retry of the same (boot, seq): skipped by the sink
        assertThat(sink.totals.get(1L)).containsExactly(0, 1, 0, 0);
    }

    @Test
    void concurrentRecordingAndFlushingWithRandomFailuresLosesAndDuplicatesNothing() throws Exception {
        int threads = 16;
        int perThread = 50_000;
        sink.mode = "random";
        AtomicBoolean recording = new AtomicBoolean(true);
        ExecutorService flusher = Executors.newSingleThreadExecutor();
        flusher.execute(() -> {
            while (recording.get()) {
                collector.flush();
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.execute(() -> {
                for (int i = 0; i < perThread; i++) {
                    collector.record(id % 4 + 1, RateLimitOutcome.values()[i % 4]);
                }
                done.countDown();
            });
        }
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        recording.set(false);
        flusher.shutdown();
        assertThat(flusher.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        sink.mode = "ok";
        while (!collector.flush()) {
            // drain
        }
        collector.flush();

        long expectedPerOutcomePerPolicy = (long) (threads / 4) * perThread / 4;
        for (long policy = 1; policy <= 4; policy++) {
            assertThat(sink.totals.get(policy)).as("policy %d", policy).containsExactly(
                    expectedPerOutcomePerPolicy, expectedPerOutcomePerPolicy, expectedPerOutcomePerPolicy, expectedPerOutcomePerPolicy);
        }
        assertThat(sink.calls.size()).as("many flushes, some failed or ambiguous").isGreaterThan(10);
    }

    @Test
    void stoppingFlushesWhatIsLeft() {
        collector.start();
        collector.record(5, RateLimitOutcome.ALLOWED);
        collector.stop();
        assertThat(sink.totals.get(5L)).containsExactly(1, 0, 0, 0);
    }
}
