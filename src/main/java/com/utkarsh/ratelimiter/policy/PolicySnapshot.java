package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.identity.Tier;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

/**
 * An immutable, pre-sorted set of enabled policies. Resolution is a linear scan over policies in
 * precedence order and returns the first match, so the most specific matching policy wins:
 *
 * <ol>
 *   <li>a policy for the caller's tier beats a policy for any tier;</li>
 *   <li>a more specific path pattern beats a less specific one ({@link PathPattern#SPECIFICITY_COMPARATOR});</li>
 *   <li>a policy for the request's method beats one for any method;</li>
 *   <li>higher {@code priority}, then lower id.</li>
 * </ol>
 *
 * No locks, no I/O: safe to call on every request from any thread.
 */
public final class PolicySnapshot {

    public enum Source { NONE, DATABASE, FALLBACK }

    static final Comparator<ResolvedPolicy> PRECEDENCE = Comparator
            .comparing((ResolvedPolicy p) -> p.tier() == null)
            .thenComparing(ResolvedPolicy::pattern, PathPattern.SPECIFICITY_COMPARATOR)
            .thenComparing(p -> p.httpMethod() == null)
            .thenComparing(ResolvedPolicy::priority, Comparator.reverseOrder())
            .thenComparingLong(ResolvedPolicy::id);

    private static final PolicySnapshot EMPTY = new PolicySnapshot(List.of(), Source.NONE, null, 0);

    private final List<ResolvedPolicy> ordered;
    private final Source source;
    private final Instant loadedAt;
    private final int skipped;

    private PolicySnapshot(List<ResolvedPolicy> ordered, Source source, Instant loadedAt, int skipped) {
        this.ordered = ordered;
        this.source = source;
        this.loadedAt = loadedAt;
        this.skipped = skipped;
    }

    public static PolicySnapshot empty() {
        return EMPTY;
    }

    public static PolicySnapshot of(Collection<ResolvedPolicy> policies, Source source, Instant loadedAt, int skipped) {
        return new PolicySnapshot(policies.stream().sorted(PRECEDENCE).toList(), source, loadedAt, skipped);
    }

    public Optional<ResolvedPolicy> resolve(HttpMethod method, String path, Tier tier) {
        PathContainer container = PathContainer.parsePath(path);
        for (ResolvedPolicy policy : ordered) {
            if (policy.matches(method, container, tier)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /** Policies in precedence order. */
    public List<ResolvedPolicy> policies() {
        return ordered;
    }

    public Source source() {
        return source;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    /** Rows that could not be compiled and were left out (logged when loaded). */
    public int skipped() {
        return skipped;
    }
}
