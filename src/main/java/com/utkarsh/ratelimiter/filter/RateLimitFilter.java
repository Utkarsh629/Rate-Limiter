package com.utkarsh.ratelimiter.filter;

import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.ClientIdentityResolver;
import com.utkarsh.ratelimiter.metrics.RateLimitOutcome;
import com.utkarsh.ratelimiter.metrics.RateLimitTelemetry;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import com.utkarsh.ratelimiter.policy.RateLimitTarget;
import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Applies rate limiting to every HTTP request. Installed inside the Spring Security filter chain
 * directly after bearer-token authentication (see SecurityConfiguration), so the JWT identity is
 * known, and before authorization, so requests that will be refused (401/403) still use quota.
 *
 * <p>Per request: excluded path? → identity → policy (in-memory snapshot) → one Redis script call via
 * {@link AlgorithmRegistry} → continue (with headers), 429, or the policy's failure mode if Redis
 * fails. No database access; exactly one Redis round trip for requests a policy covers, none otherwise.
 *
 * <p>Runs once per request: error and async re-dispatches are not rate limited again
 * ({@link OncePerRequestFilter} defaults).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long STORE_FAILURE_LOG_INTERVAL_MS = 10_000;

    private final ClientIdentityResolver identities;
    private final PolicyResolver policies;
    private final AlgorithmRegistry algorithms;
    private final RateLimitResponseWriter writer;
    private final RateLimitFilterProperties properties;
    private final FailureMode defaultFailureMode;
    private final List<PathPattern> excluded;
    private final UrlPathHelper paths = UrlPathHelper.defaultInstance;

    private final RateLimitTelemetry telemetry;
    private final AtomicLong lastStoreFailureLog = new AtomicLong();
    private final AtomicLong suppressedStoreFailureLogs = new AtomicLong();

    public RateLimitFilter(ClientIdentityResolver identities, PolicyResolver policies, AlgorithmRegistry algorithms,
                           RateLimitResponseWriter writer, RateLimitFilterProperties properties, FailureMode defaultFailureMode) {
        this(identities, policies, algorithms, writer, properties, defaultFailureMode, null);
    }

    /**
     * @param telemetry records each decision (metrics and /stats); {@code null} records nothing. Recording is
     *                  observation only: it never changes a response (Phase 3A, decision P1).
     */
    public RateLimitFilter(ClientIdentityResolver identities, PolicyResolver policies, AlgorithmRegistry algorithms,
                           RateLimitResponseWriter writer, RateLimitFilterProperties properties, FailureMode defaultFailureMode,
                           RateLimitTelemetry telemetry) {
        this.telemetry = telemetry;
        this.identities = identities;
        this.policies = policies;
        this.algorithms = algorithms;
        this.writer = writer;
        this.properties = properties;
        this.defaultFailureMode = defaultFailureMode;
        this.excluded = properties.excludedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(paths.getPathWithinApplication(request));
        return excluded.stream().anyMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime(); // rate-limiter overhead: identity + policy + algorithm + Redis
        ClientIdentity identity = identities.resolve(request);
        Optional<RateLimitTarget> target = policies.resolve(
                HttpMethod.valueOf(request.getMethod()), paths.getPathWithinApplication(request), identity);
        if (target.isEmpty()) {
            chain.doFilter(request, response); // no policy covers this request: not rate limited, no headers
            return;
        }

        RateLimitResult result;
        try {
            result = algorithms.tryAcquire(target.get().key(), target.get().policy().rule());
        } catch (RateLimitStoreException e) {
            handleStoreFailure(request, response, chain, target.get(), e, start);
            return;
        }

        if (result.allowed()) {
            record(target.get(), RateLimitOutcome.ALLOWED, start);
            writer.writeHeaders(response, result); // before the body is committed
            chain.doFilter(request, response);
        } else {
            record(target.get(), RateLimitOutcome.REJECTED, start);
            writer.writeRejected(request, response, result); // the chain (and the controller) is not invoked
        }
    }

    private void record(RateLimitTarget target, RateLimitOutcome outcome, long start) {
        if (telemetry != null) {
            telemetry.record(target.policy(), outcome, System.nanoTime() - start);
        }
    }

    private void handleStoreFailure(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                    RateLimitTarget target, RateLimitStoreException e, long start) throws ServletException, IOException {
        FailureMode mode = target.policy().failureModeOverride().orElse(defaultFailureMode);
        logStoreFailure(mode, target, e);
        record(target, mode == FailureMode.FAIL_CLOSED ? RateLimitOutcome.FAIL_CLOSED : RateLimitOutcome.FAIL_OPEN, start);
        if (mode == FailureMode.FAIL_CLOSED) {
            writer.writeUnavailable(request, response, properties.failClosedRetryAfter().toSeconds());
            return;
        }
        // No X-RateLimit-* headers: the quota is unknown, and inventing numbers would mislead clients.
        chain.doFilter(request, response);
    }

    /** The exception message plus its root cause, so operators see why the store failed (logging only). */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? e.getMessage() : e.getMessage() + " (root cause: " + root.getClass().getName() + ": " + root.getMessage() + ")";
    }

    /** At most one WARN per 10 s, so a Redis outage at thousands of requests per second cannot flood the logs. */
    private void logStoreFailure(FailureMode mode, RateLimitTarget target, RateLimitStoreException e) {
        long now = System.currentTimeMillis();
        long last = lastStoreFailureLog.get();
        if (now - last >= STORE_FAILURE_LOG_INTERVAL_MS && lastStoreFailureLog.compareAndSet(last, now)) {
            log.warn("Rate-limit store unavailable; {} (policy '{}'; {} similar events suppressed since the last message): {}",
                    mode == FailureMode.FAIL_OPEN ? "FAIL_OPEN: allowing requests without limits" : "FAIL_CLOSED: rejecting with 503",
                    target.policy().name(), suppressedStoreFailureLogs.getAndSet(0), describe(e));
        } else {
            suppressedStoreFailureLogs.incrementAndGet();
        }
    }
}
