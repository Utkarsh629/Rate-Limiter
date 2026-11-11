package com.utkarsh.ratelimiter.stats;

import com.utkarsh.ratelimiter.metrics.RateLimitOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Counts decisions per policy in memory and flushes them to this instance's Redis shard.
 *
 * <p><b>Concurrency-safe snapshot-and-flush, exactly once:</b>
 * <ul>
 *   <li>Request threads only increment monotonic {@link LongAdder}s: no locks, no I/O, never reset.</li>
 *   <li>A single flusher (synchronized) turns "sum − already-batched watermark" into a batch and moves the
 *       watermark at that moment. An increment racing with the snapshot is either in this batch or, since
 *       the counter is monotonic and the watermark only moves by what was batched, in the next one: never
 *       lost and never counted twice.</li>
 *   <li>A batch that fails is kept and retried <b>unchanged</b> (same boot id and sequence) before any new batch
 *       is built. The Redis script applies a (boot, seq) at most once per shard, so a retry after an
 *       ambiguous failure (Redis paused: the client timed out, but the command ran later) is not double-counted.</li>
 *   <li>Redis down for a long time: the pending batch waits; new increments keep accumulating in the adders
 *       and go out in the next batch. Nothing is lost while the instance lives; at most one flush interval of
 *       counts is lost if it crashes.</li>
 * </ul>
 * Flushes do not go through the rate-limit circuit breaker.
 */
public class PolicyStatsCollector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PolicyStatsCollector.class);
    private static final int OUTCOMES = RateLimitOutcome.values().length;

    private final StatsSink sink;
    private final Duration flushInterval;
    private final String bootId = UUID.randomUUID().toString();
    private final Map<Long, PolicyCounters> counters = new ConcurrentHashMap<>();
    private final Counter flushSuccess;
    private final Counter flushFailure;
    private final AtomicLong lastFailureLog = new AtomicLong();

    // flusher-only state (guarded by "this")
    private long seq;
    private StatsBatch pending;

    private volatile ScheduledExecutorService executor;

    private static final class PolicyCounters {
        final LongAdder[] totals = new LongAdder[OUTCOMES];
        final long[] batched = new long[OUTCOMES]; // flusher-only

        PolicyCounters() {
            for (int i = 0; i < OUTCOMES; i++) {
                totals[i] = new LongAdder();
            }
        }
    }

    public PolicyStatsCollector(StatsSink sink, Duration flushInterval, MeterRegistry meters) {
        this.sink = sink;
        this.flushInterval = flushInterval;
        this.flushSuccess = Counter.builder("ratelimiter.stats.flushes").tag("result", "success").register(meters);
        this.flushFailure = Counter.builder("ratelimiter.stats.flushes").tag("result", "failure").register(meters);
    }

    /** Hot path: one map lookup and one LongAdder increment. */
    public void record(long policyId, RateLimitOutcome outcome) {
        counters.computeIfAbsent(policyId, id -> new PolicyCounters()).totals[outcome.ordinal()].increment();
    }

    /** @return true if nothing is left to send (everything counted so far has been applied) */
    public synchronized boolean flush() {
        if (pending == null) {
            pending = nextBatch();
            if (pending == null) {
                return true;
            }
        }
        try {
            sink.apply(pending);
            pending = null;
            flushSuccess.increment();
            return true;
        } catch (RuntimeException e) {
            flushFailure.increment();
            long now = System.currentTimeMillis();
            long last = lastFailureLog.get();
            if (now - last > 30_000 && lastFailureLog.compareAndSet(last, now)) {
                log.warn("Statistics flush failed; batch {} kept for retry (counts are not lost while this instance runs): {}",
                        pending.seq(), e.toString());
            }
            return false;
        }
    }

    private StatsBatch nextBatch() {
        Map<Long, long[]> deltas = new HashMap<>();
        counters.forEach((policyId, c) -> {
            long[] delta = new long[OUTCOMES];
            boolean any = false;
            for (int i = 0; i < OUTCOMES; i++) {
                long sum = c.totals[i].sum();      // monotonic; may miss in-flight increments -> next batch
                delta[i] = sum - c.batched[i];
                c.batched[i] = sum;
                any |= delta[i] != 0;
            }
            if (any) {
                deltas.put(policyId, delta);
            }
        });
        return deltas.isEmpty() ? null : new StatsBatch(bootId, ++seq, deltas);
    }

    String bootId() {
        return bootId;
    }

    // ---------- lifecycle: periodic flush, final flush on shutdown ----------

    @Override
    public synchronized void start() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stats-flush");
                t.setDaemon(true);
                return t;
            });
            long ms = flushInterval.toMillis();
            executor.scheduleWithFixedDelay(this::flushQuietly, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Unexpected statistics flush error", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            flushQuietly(); // best effort: send what is left before the instance goes away
            if (pending != null) {
                flushQuietly();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** Stop after the web server (higher phase stops first), so late requests are still flushed. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 100;
    }
}
