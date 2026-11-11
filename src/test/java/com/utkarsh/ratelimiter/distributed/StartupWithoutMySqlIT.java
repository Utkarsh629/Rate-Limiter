package com.utkarsh.ratelimiter.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.utkarsh.ratelimiter.RateLimiterApplication;
import com.utkarsh.ratelimiter.config.DeferredFlywayMigration;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.PolicyCache;
import com.utkarsh.ratelimiter.policy.PolicySnapshot;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * Starting while MySQL is unreachable, with the production JPA settings (no schema access at boot):
 * <ul>
 *   <li>fallback disabled (production): the instance starts NOT READY with no policies, then becomes
 *       READY by itself once MySQL appears (deferred Flyway migration + policy load on the periodic retry);</li>
 *   <li>fallback enabled (development): configured fallback policies are served immediately.</li>
 * </ul>
 */
class StartupWithoutMySqlIT {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE).withExposedPorts(6379);
    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
    private static MySQLContainer<?> lateMySql;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopAll() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        if (lateMySql != null) {
            lateMySql.stop();
        }
        REDIS.stop();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ConfigurableApplicationContext start(int mysqlPort, boolean fallback, String... extra) {
        List<String> props = new ArrayList<>(List.of(
                "spring.datasource.url=jdbc:mysql://localhost:" + mysqlPort + "/ratelimiter?sslMode=DISABLED&allowPublicKeyRetrieval=true",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                "spring.datasource.hikari.connection-timeout=1000",
                // production JPA settings: nothing touches the database while booting
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
                "spring.data.redis.host=" + REDIS.getHost(),
                "spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "server.port=0",
                "ratelimiter.instance-id=no-mysql-" + mysqlPort,
                "ratelimiter.policy.reload-interval=1s",
                "ratelimiter.policy.fallback-enabled=" + fallback));
        props.addAll(List.of(extra));
        // Command-line arguments (not builder default properties, which application.yml would override).
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RateLimiterApplication.class)
                .profiles("test").run(props.stream().map(p -> "--" + p).toArray(String[]::new));
        CONTEXTS.add(context);
        return context;
    }

    private static int status(ConfigurableApplicationContext context, String path) {
        RestTemplate rest = new RestTemplateBuilder().build();
        String url = "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + path;
        try {
            return rest.getForEntity(url, String.class).getStatusCode().value();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            HttpStatusCode code = e.getStatusCode();
            return code.value();
        }
    }

    @Test
    void productionStartsNotReadyThenRecoversWhenMySqlAppears() throws IOException {
        int port = freePort();
        ConfigurableApplicationContext app = start(port, false);
        PolicyCache cache = app.getBean(PolicyCache.class);

        assertThat(cache.isInitialized()).isFalse();
        assertThat(app.getBean(DeferredFlywayMigration.class).isMigrated()).isFalse();
        assertThat(status(app, "/actuator/health/readiness")).isEqualTo(503);
        assertThat(status(app, "/actuator/health/liveness")).isEqualTo(200);

        lateMySql = new MySQLContainer<>(InfrastructureContainers.MYSQL_IMAGE).withDatabaseName("ratelimiter")
                .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(cmd.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(3306)))));
        lateMySql.start();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> status(app, "/actuator/health/readiness") == 200);
        assertThat(app.getBean(DeferredFlywayMigration.class).isMigrated()).isTrue();
        assertThat(cache.snapshot().source()).isEqualTo(PolicySnapshot.Source.DATABASE);
        assertThat(cache.snapshot().policies()).extracting(ResolvedPolicy::name)
                .containsExactlyInAnyOrder("anonymous-default", "free-tier", "premium-tier", "enterprise-tier",
                        "demo-enterprise-endpoint");
    }

    @Test
    void developmentServesFallbackPoliciesWhileMySqlIsDown() throws IOException {
        ConfigurableApplicationContext app = start(freePort(), true,
                "ratelimiter.policy.fallback-policies[0].id=1000000002",
                "ratelimiter.policy.fallback-policies[0].name=fallback-free",
                "ratelimiter.policy.fallback-policies[0].endpoint-pattern=/api/**",
                "ratelimiter.policy.fallback-policies[0].tier=FREE",
                "ratelimiter.policy.fallback-policies[0].key-by=USER",
                "ratelimiter.policy.fallback-policies[0].algorithm=TOKEN_BUCKET",
                "ratelimiter.policy.fallback-policies[0].limit=20",
                "ratelimiter.policy.fallback-policies[0].window-ms=60000");
        PolicyCache cache = app.getBean(PolicyCache.class);

        assertThat(cache.isInitialized()).isTrue();
        assertThat(cache.snapshot().source()).isEqualTo(PolicySnapshot.Source.FALLBACK);
        assertThat(cache.snapshot().resolve(HttpMethod.GET, "/api/v1/demo/hello", Tier.FREE))
                .map(ResolvedPolicy::name).contains("fallback-free");
        // R1: fallback policies are usable, so the instance is ready; the database outage is visible
        // separately in the dependencies group. (Before R1 this was 503 because db was in readiness.)
        assertThat(status(app, "/actuator/health/readiness")).isEqualTo(200);
        assertThat(status(app, "/actuator/health/dependencies")).isEqualTo(503);
    }
}
