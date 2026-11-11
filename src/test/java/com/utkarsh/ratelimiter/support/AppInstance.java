package com.utkarsh.ratelimiter.support;

import com.utkarsh.ratelimiter.RateLimiterApplication;
import com.utkarsh.ratelimiter.security.JwtProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * One real application instance (own Tomcat, Hikari pool, Lettuce connections, policy cache) bound
 * to shared MySQL and Redis containers, plus a plain JDK HTTP client to call it.
 */
public final class AppInstance implements AutoCloseable {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final ConfigurableApplicationContext context;
    private final String baseUrl;
    private final TestTokens tokens;

    private AppInstance(ConfigurableApplicationContext context) {
        this.context = context;
        this.baseUrl = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
        this.tokens = new TestTokens(context.getBean(JwtProperties.class));
    }

    /** Settings are passed as command-line arguments so application.yml cannot override them. */
    public static AppInstance start(MySQLContainer<?> mysql, GenericContainer<?> redis, String instanceId, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + mysql.getJdbcUrl(),
                "--spring.datasource.username=" + mysql.getUsername(),
                "--spring.datasource.password=" + mysql.getPassword(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--server.port=0",
                "--ratelimiter.instance-id=" + instanceId));
        args.addAll(List.of(extraArgs));
        return new AppInstance(new SpringApplicationBuilder(RateLimiterApplication.class).profiles("test").run(args.toArray(String[]::new)));
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public TestTokens tokens() {
        return tokens;
    }

    public HttpResponse<String> get(String path, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request.build());
    }

    public HttpResponse<String> send(String method, String path, String bearerToken, String jsonBody) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}
