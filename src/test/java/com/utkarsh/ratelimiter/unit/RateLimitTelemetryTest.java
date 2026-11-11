package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.metrics.RateLimitOutcome;
import com.utkarsh.ratelimiter.metrics.RateLimitTelemetry;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.stats.PolicyStatsCollector;
import com.utkarsh.ratelimiter.stats.StatsBatch;
import com.utkarsh.ratelimiter.support.PrometheusText;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The 3A.1 meters as Prometheus sees them: names, bounded labels, histogram range, and stats hand-off. */
class RateLimitTelemetryTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final List<StatsBatch> flushed = new ArrayList<>();
    private final PolicyStatsCollector stats = new PolicyStatsCollector(flushed::add, Duration.ofSeconds(5), registry);
    private final RateLimitTelemetry telemetry = new RateLimitTelemetry(registry, stats);

    private static ResolvedPolicy policy(long id, AlgorithmType algorithm) {
        return ResolvedPolicy.compile(id, "p" + id, null, "/api/**", Tier.FREE, KeyBy.USER, algorithm, 10, 60_000, null, null, 0, 0);
    }

    @Test
    void recordsEveryOutcomeWithBoundedTags() {
        ResolvedPolicy tb = policy(1, AlgorithmType.TOKEN_BUCKET);
        ResolvedPolicy sw = policy(2, AlgorithmType.SLIDING_WINDOW);
        telemetry.record(tb, RateLimitOutcome.ALLOWED, 120_000);
        telemetry.record(tb, RateLimitOutcome.ALLOWED, 80_000);
        telemetry.record(tb, RateLimitOutcome.REJECTED, 90_000);
        telemetry.record(sw, RateLimitOutcome.FAIL_OPEN, 50_000_000);
        telemetry.record(sw, RateLimitOutcome.FAIL_CLOSED, 51_000_000);

        PrometheusText text = new PrometheusText(registry.scrape());
        assertThat(text.sum("ratelimiter_requests_total", "algorithm", "token_bucket", "result", "allowed")).isEqualTo(2);
        assertThat(text.sum("ratelimiter_requests_total", "algorithm", "token_bucket", "result", "rejected")).isEqualTo(1);
        assertThat(text.sum("ratelimiter_requests_total", "algorithm", "sliding_window", "result", "fail_open")).isEqualTo(1);
        assertThat(text.sum("ratelimiter_requests_total", "algorithm", "sliding_window", "result", "fail_closed")).isEqualTo(1);
        assertThat(text.sum("ratelimiter_allowed_total", "algorithm", "token_bucket")).isEqualTo(2);
        assertThat(text.sum("ratelimiter_rejected_total", "algorithm", "token_bucket")).isEqualTo(1);
        assertThat(text.sum("ratelimiter_allowed_total")).as("fail-open is not 'allowed'").isEqualTo(2);
        assertThat(text.sum("ratelimiter_request_duration_seconds_count", "algorithm", "token_bucket")).isEqualTo(3);
        assertThat(text.sum("ratelimiter_request_duration_seconds_sum", "algorithm", "token_bucket", "result", "allowed"))
                .isCloseTo(0.0002, org.assertj.core.data.Offset.offset(1e-9));

        Set<String> labelKeys = text.samples().stream().filter(s -> s.name().startsWith("ratelimiter_"))
                .flatMap(s -> s.labels().keySet().stream()).collect(Collectors.toSet());
        assertThat(labelKeys).isSubsetOf("algorithm", "result", "le", "outcome", "type");
    }

    @Test
    void histogramResolvesSubMillisecondDecisions() {
        telemetry.record(policy(1, AlgorithmType.FIXED_WINDOW), RateLimitOutcome.ALLOWED, 60_000);
        double smallestBucket = new PrometheusText(registry.scrape()).samples().stream()
                .filter(s -> s.name().equals("ratelimiter_request_duration_seconds_bucket"))
                .mapToDouble(s -> Double.parseDouble(s.labels().get("le").replace("+Inf", "Infinity")))
                .min().orElseThrow();
        assertThat(smallestBucket).isLessThanOrEqualTo(RateLimitTelemetry.MIN_EXPECTED.toNanos() / 1e9);
    }

    private long ratelimiterSeries() {
        return new PrometheusText(registry.scrape()).samples().stream().filter(s -> s.name().startsWith("ratelimiter_")).count();
    }

    @Test
    void seriesCountDoesNotGrowWithPoliciesOrTraffic() {
        telemetry.record(policy(1, AlgorithmType.TOKEN_BUCKET), RateLimitOutcome.ALLOWED, 1_000_000);
        long afterOne = ratelimiterSeries();
        for (long id = 2; id <= 500; id++) { // 499 more policies, all outcomes, all algorithms
            telemetry.record(policy(id, AlgorithmType.values()[(int) (id % 3)]), RateLimitOutcome.values()[(int) (id % 4)], 1_000_000);
        }
        assertThat(ratelimiterSeries()).isEqualTo(afterOne); // every series exists up front; none per policy/user
    }

    @Test
    void decisionsAreHandedToTheStatsCollector() {
        telemetry.record(policy(9, AlgorithmType.TOKEN_BUCKET), RateLimitOutcome.ALLOWED, 1);
        telemetry.record(policy(9, AlgorithmType.TOKEN_BUCKET), RateLimitOutcome.FAIL_CLOSED, 1);
        stats.flush();
        assertThat(flushed).hasSize(1);
        assertThat(flushed.get(0).deltas().get(9L)).containsExactly(1, 0, 0, 1);
    }
}
