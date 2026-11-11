package com.utkarsh.ratelimiter.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.RateLimiterApplication;
import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.PolicyCache;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.security.JwtProperties;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import com.utkarsh.ratelimiter.support.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * Policy changes made through one instance's API reach every instance's in-memory snapshot:
 * <ul>
 *   <li>via Redis Pub/Sub within about a second (periodic reload set to 10 minutes, so it cannot be the cause);</li>
 *   <li>via the periodic reload alone when no message is sent (change written straight to MySQL).</li>
 * </ul>
 * Three real application contexts (own web server, Hikari pool, Lettuce connections, listener
 * container) share one MySQL and one Redis container, as three deployed instances would.
 */
class PolicyPropagationIT {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE).withExposedPorts(6379);
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(InfrastructureContainers.MYSQL_IMAGE).withDatabaseName("ratelimiter");
    private final List<ConfigurableApplicationContext> instances = new ArrayList<>();

    @BeforeAll
    static void startContainers() {
        REDIS.start();
        MYSQL.start();
    }

    @AfterAll
    static void stopContainers() {
        MYSQL.stop();
        REDIS.stop();
    }

    @AfterEach
    void stopInstances() {
        instances.forEach(ConfigurableApplicationContext::close);
        instances.clear();
    }

    private ConfigurableApplicationContext startInstance(String id, Duration reloadInterval) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RateLimiterApplication.class)
                .profiles("test")
                // Command-line arguments: highest precedence, so application.yml cannot override them.
                .run("--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--server.port=0",
                        "--ratelimiter.instance-id=" + id,
                        "--ratelimiter.policy.reload-interval=" + reloadInterval.toSeconds() + "s");
        instances.add(context);
        return context;
    }

    private static Optional<ResolvedPolicy> policyNamed(ConfigurableApplicationContext instance, String name) {
        return instance.getBean(PolicyCache.class).snapshot().policies().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> adminCall(ConfigurableApplicationContext instance, HttpMethod method, String path, Object body) {
        RestTemplate rest = new RestTemplateBuilder().build();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(new TestTokens(instance.getBean(JwtProperties.class)).admin());
        String url = "http://localhost:" + instance.getEnvironment().getProperty("local.server.port") + path;
        return rest.exchange(url, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    @Test
    void changesThroughOneInstancePropagateToAllViaPubSub() {
        ConfigurableApplicationContext a = startInstance("app-1", Duration.ofMinutes(10));
        ConfigurableApplicationContext b = startInstance("app-2", Duration.ofMinutes(10));
        ConfigurableApplicationContext c = startInstance("app-3", Duration.ofMinutes(10));
        List<ConfigurableApplicationContext> all = List.of(a, b, c);
        Duration propagation = Duration.ofSeconds(3);

        // CREATE on instance 1 -> visible on 2 and 3
        Map<String, Object> request = new HashMap<>(Map.of("name", "it-propagation", "endpointPattern", "/api/v1/reports/**",
                "tier", "FREE", "keyBy", "USER", "algorithm", "FIXED_WINDOW", "limit", 3, "windowMs", 60_000));
        Map<String, Object> created = adminCall(a, HttpMethod.POST, "/api/v1/policies", request);
        long id = ((Number) created.get("id")).longValue();
        for (ConfigurableApplicationContext instance : all) {
            await().atMost(propagation).until(() -> policyNamed(instance, "it-propagation").isPresent());
        }
        ClientIdentity freeUser = new ClientIdentity("u-1", Tier.FREE, "203.0.113.1");
        assertThat(c.getBean(PolicyResolver.class).resolve(HttpMethod.GET, "/api/v1/reports/7", freeUser))
                .get().extracting(t -> t.policy().name()).isEqualTo("it-propagation");

        // UPDATE on instance 2 -> new limit everywhere
        request.put("limit", 9);
        request.put("version", 0);
        adminCall(b, HttpMethod.PUT, "/api/v1/policies/" + id, request);
        for (ConfigurableApplicationContext instance : all) {
            await().atMost(propagation).until(() -> policyNamed(instance, "it-propagation").map(p -> p.rule().limit()).orElse(0L) == 9L);
        }

        // DELETE on instance 3 -> gone everywhere; FREE users fall back to the tier default
        adminCall(c, HttpMethod.DELETE, "/api/v1/policies/" + id, null);
        for (ConfigurableApplicationContext instance : all) {
            await().atMost(propagation).until(() -> policyNamed(instance, "it-propagation").isEmpty());
        }
        assertThat(a.getBean(PolicyResolver.class).resolve(HttpMethod.GET, "/api/v1/reports/7", freeUser))
                .get().extracting(t -> t.policy().name()).isEqualTo("free-tier");
    }

    @Test
    void periodicReloadCatchesChangesThatProducedNoMessage() {
        ConfigurableApplicationContext a = startInstance("app-1", Duration.ofSeconds(1));
        ConfigurableApplicationContext b = startInstance("app-2", Duration.ofSeconds(1));

        // Written directly to MySQL: no API call, no event, no pub/sub message.
        a.getBean(JdbcTemplate.class).update("UPDATE rate_limit_policy SET limit_value = 25 WHERE name = 'free-tier'");
        try {
            for (ConfigurableApplicationContext instance : List.of(a, b)) {
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> policyNamed(instance, "free-tier").map(p -> p.rule().limit()).orElse(0L) == 25L);
            }
        } finally {
            a.getBean(JdbcTemplate.class).update("UPDATE rate_limit_policy SET limit_value = 20 WHERE name = 'free-tier'");
        }
    }
}
