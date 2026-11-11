package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.metrics.RateLimitTelemetry;
import com.utkarsh.ratelimiter.support.ApiTestSupport;
import com.utkarsh.ratelimiter.support.PrometheusText;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 3A.1 and 3A.2 through the real application: /actuator/prometheus and /api/v1/policies/{id}/stats.
 * {@code @AutoConfigureObservability}: Spring Boot tests otherwise replace the Prometheus registry.
 */
@AutoConfigureObservability
class ObservabilityIT extends ApiTestSupport {

    private PrometheusText scrape() {
        ResponseEntity<String> response = http.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).as("no token needed; Nginx blocks it externally").isEqualTo(HttpStatus.OK);
        return new PrometheusText(response.getBody());
    }

    @Test
    void allRequiredMetersAreExposedWithBoundedLabels() {
        get("/api/v1/demo/profile", tokens.user("m-" + UUID.randomUUID(), Tier.FREE));
        PrometheusText text = scrape();
        Set<String> names = text.samples().stream().map(PrometheusText.Sample::name).collect(Collectors.toSet());
        assertThat(names).contains(
                "ratelimiter_requests_total", "ratelimiter_allowed_total", "ratelimiter_rejected_total",
                "ratelimiter_request_duration_seconds_bucket", "ratelimiter_redis_duration_seconds_bucket",
                "ratelimiter_redis_errors_total", "ratelimiter_algorithm_executions_total",
                "resilience4j_circuitbreaker_state", "http_server_requests_seconds_bucket");
        Set<String> labelKeys = text.samples().stream().filter(s -> s.name().startsWith("ratelimiter_"))
                .flatMap(s -> s.labels().keySet().stream()).collect(Collectors.toSet());
        assertThat(labelKeys).isSubsetOf("application", "algorithm", "result", "outcome", "type", "from", "to", "le");
        for (String histogram : List.of("ratelimiter_request_duration_seconds_bucket", "ratelimiter_redis_duration_seconds_bucket")) {
            double smallest = text.samples().stream().filter(s -> s.name().equals(histogram))
                    .mapToDouble(s -> Double.parseDouble(s.labels().get("le").replace("+Inf", "Infinity"))).min().orElseThrow();
            assertThat(smallest).as(histogram).isLessThanOrEqualTo(RateLimitTelemetry.MIN_EXPECTED.toNanos() / 1e9);
        }
    }

    @Test
    void countersReconcileExactlyWithTheRequestsSent() {
        String user = tokens.user("reconcile-" + UUID.randomUUID(), Tier.FREE);
        PrometheusText before = scrape();
        int allowed = 0;
        int rejected = 0;
        for (int i = 0; i < 23; i++) { // free-tier: 20 tokens, then 429s
            HttpStatus status = (HttpStatus) get("/api/v1/demo/profile", user).getStatusCode();
            allowed += status == HttpStatus.OK ? 1 : 0;
            rejected += status == HttpStatus.TOO_MANY_REQUESTS ? 1 : 0;
        }
        PrometheusText after = scrape();

        assertThat(allowed + rejected).isEqualTo(23);
        assertThat(delta(before, after, "ratelimiter_requests_total", "algorithm", "token_bucket", "result", "allowed")).isEqualTo(allowed);
        assertThat(delta(before, after, "ratelimiter_requests_total", "algorithm", "token_bucket", "result", "rejected")).isEqualTo(rejected);
        assertThat(delta(before, after, "ratelimiter_requests_total", "result", "fail_open")).isZero();
        assertThat(delta(before, after, "ratelimiter_allowed_total", "algorithm", "token_bucket")).isEqualTo(allowed);
        assertThat(delta(before, after, "ratelimiter_rejected_total", "algorithm", "token_bucket")).isEqualTo(rejected);
        assertThat(delta(before, after, "ratelimiter_algorithm_executions_total", "algorithm", "token_bucket")).isEqualTo(23);
        assertThat(delta(before, after, "ratelimiter_redis_duration_seconds_count", "algorithm", "token_bucket", "outcome", "success")).isEqualTo(23);
        assertThat(delta(before, after, "ratelimiter_request_duration_seconds_count", "algorithm", "token_bucket")).isEqualTo(23);
    }

    private static double delta(PrometheusText before, PrometheusText after, String name, String... labels) {
        return after.sum(name, labels) - before.sum(name, labels);
    }

    // ---------- /stats ----------

    @Test
    void statsAggregateTheDecisionsOfADedicatedPolicy() {
        Map<String, Object> policy = new HashMap<>(Map.of("name", "it-stats", "httpMethod", "GET", "endpointPattern", "/api/v1/demo/premium",
                "tier", "PREMIUM", "keyBy", "USER", "algorithm", "FIXED_WINDOW", "limit", 4, "windowMs", 3_600_000));
        ResponseEntity<Map<String, Object>> created = callForMap(HttpMethod.POST, "/api/v1/policies", tokens.admin(), policy);
        long id = ((Number) created.getBody().get("id")).longValue();
        await().until(() -> policyCache.snapshot().policies().stream().anyMatch(p -> p.id() == id));

        String user = tokens.user("stats-" + UUID.randomUUID(), Tier.PREMIUM);
        for (int i = 0; i < 7; i++) {
            get("/api/v1/demo/premium", user); // 4 allowed, 3 rejected
        }

        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
            Map<String, Object> stats = callForMap(HttpMethod.GET, "/api/v1/policies/" + id + "/stats", tokens.admin(), null).getBody();
            assertThat(stats).containsEntry("allowed", 4).containsEntry("rejected", 3).containsEntry("failOpen", 0)
                    .containsEntry("failClosed", 0).containsEntry("policyId", (int) id);
            assertThat((Double) stats.get("rejectionRate")).isEqualTo(3.0 / 7);
            assertThat((List<?>) stats.get("instances")).hasSize(1);
            assertThat(stats.get("consistency").toString()).contains("eventual");
        });
    }

    @Test
    void statsRequireAnAdminAndAnExistingPolicy() {
        assertThat(callForMap(HttpMethod.GET, "/api/v1/policies/2/stats", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callForMap(HttpMethod.GET, "/api/v1/policies/2/stats", tokens.user("u", Tier.ENTERPRISE), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(callForMap(HttpMethod.GET, "/api/v1/policies/999999/stats", tokens.admin(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(callForMap(HttpMethod.GET, "/api/v1/policies/2/stats", tokens.admin(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
