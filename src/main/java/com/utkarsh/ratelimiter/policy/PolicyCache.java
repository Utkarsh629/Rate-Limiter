package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.config.RateLimiterProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Holds the current {@link PolicySnapshot} and keeps it fresh.
 *
 * <ul>
 *   <li><b>Reads</b> are a volatile read of an immutable snapshot: no locks, no I/O.</li>
 *   <li><b>Reloads</b> run on one background thread: at startup, on invalidation (local change or
 *       Redis pub/sub), and every {@code reload-interval} as a safety net for missed messages.
 *       Requests arriving while a reload is queued coalesce into that one reload.</li>
 *   <li><b>A failed reload keeps the last good snapshot.</b> If no snapshot was ever loaded from the
 *       database: with {@code fallback-enabled} (development) the configured fallback policies are
 *       installed; otherwise the cache stays uninitialised and readiness reports DOWN.</li>
 * </ul>
 */
public class PolicyCache implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PolicyCache.class);

    private final PolicySource source;
    private final List<ResolvedPolicy> fallbackPolicies;
    private final boolean fallbackEnabled;
    private final Duration reloadInterval;
    private final Clock clock;

    private final AtomicReference<PolicySnapshot> current = new AtomicReference<>(PolicySnapshot.empty());
    private final AtomicBoolean reloadQueued = new AtomicBoolean();
    private final AtomicLong successfulReloads = new AtomicLong();
    private final AtomicLong failedReloads = new AtomicLong();
    private volatile String lastError;
    private volatile Instant lastAttemptAt;
    private volatile ScheduledExecutorService executor;

    public PolicyCache(PolicySource source, RateLimiterProperties properties, List<ResolvedPolicy> fallbackPolicies, Clock clock) {
        this.source = source;
        this.fallbackPolicies = List.copyOf(fallbackPolicies);
        this.fallbackEnabled = properties.policy().fallbackEnabled();
        this.reloadInterval = properties.policy().reloadInterval();
        this.clock = clock;
    }

    public PolicySnapshot snapshot() {
        return current.get();
    }

    public boolean isInitialized() {
        return current.get().source() != PolicySnapshot.Source.NONE;
    }

    /** Asynchronously reloads; calls made while a reload is already queued are coalesced. */
    public void requestReload(String reason) {
        ScheduledExecutorService ex = executor;
        if (ex != null && reloadQueued.compareAndSet(false, true)) {
            ex.execute(() -> {
                reloadQueued.set(false);
                reload(reason);
            });
        }
    }

    /** Synchronous reload. Returns true if a database snapshot was installed. */
    public synchronized boolean reload(String reason) {
        lastAttemptAt = clock.instant();
        try {
            PolicySource.Loaded loaded = source.load();
            PolicySnapshot next = PolicySnapshot.of(loaded.policies(), PolicySnapshot.Source.DATABASE, clock.instant(), loaded.skipped());
            PolicySnapshot previous = current.getAndSet(next);
            successfulReloads.incrementAndGet();
            lastError = null;
            if (previous.source() != PolicySnapshot.Source.DATABASE) {
                log.info("Policy snapshot loaded from database ({}): {} policies", reason, next.policies().size());
            } else {
                log.debug("Policy snapshot reloaded ({}): {} policies", reason, next.policies().size());
            }
            return true;
        } catch (RuntimeException e) {
            failedReloads.incrementAndGet();
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (current.get().source() == PolicySnapshot.Source.NONE && fallbackEnabled) {
                current.set(PolicySnapshot.of(fallbackPolicies, PolicySnapshot.Source.FALLBACK, clock.instant(), 0));
                log.warn("Policy database unavailable ({}); serving {} FALLBACK policies (development only). Cause: {}",
                        reason, fallbackPolicies.size(), lastError);
            } else if (current.get().source() == PolicySnapshot.Source.NONE) {
                log.warn("Policy database unavailable ({}); no policies loaded, instance stays NOT READY. Cause: {}", reason, lastError);
            } else {
                log.warn("Policy reload failed ({}); keeping {} snapshot from {}. Cause: {}",
                        reason, current.get().source(), current.get().loadedAt(), lastError);
            }
            return false;
        }
    }

    public String lastError() {
        return lastError;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public long successfulReloads() {
        return successfulReloads.get();
    }

    public long failedReloads() {
        return failedReloads.get();
    }

    // ---------- lifecycle: initial load, then periodic reload ----------

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "policy-reload");
            t.setDaemon(true);
            return t;
        });
        reload("startup");
        long periodMs = reloadInterval.toMillis();
        executor.scheduleWithFixedDelay(() -> reload("periodic"), periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
