package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.demo.IdentityEchoController;
import com.utkarsh.ratelimiter.security.DemoTokenController;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/** Development endpoints (demo token, identity echo, OpenAPI) are absent unless explicitly enabled, and never with prod. */
class DevEndpointsDisabledIT {

    static void assertNoDevEndpoints(ApplicationContext context, TestRestTemplate http) {
        assertThat(context.getBeanNamesForType(DemoTokenController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(IdentityEchoController.class)).isEmpty();
        assertThat(http.postForEntity("/api/v1/auth/demo-token", Map.of("userId", "x", "tier", "FREE"), String.class)
                .getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/v1/demo/whoami", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ratelimiter.dev-endpoints.enabled=false")
    @ActiveProfiles("test")
    @Import(InfrastructureContainers.class)
    class WhenDisabled {
        @Autowired ApplicationContext context;
        @Autowired TestRestTemplate http;

        @Test
        void devEndpointsDoNotExist() {
            assertNoDevEndpoints(context, http);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ratelimiter.dev-endpoints.enabled=true")
    @ActiveProfiles({"test", "prod"})
    @Import(InfrastructureContainers.class)
    class WithProdProfileEvenIfThePropertyIsForcedOn {
        @Autowired ApplicationContext context;
        @Autowired TestRestTemplate http;

        @Test
        void devEndpointsDoNotExist() {
            assertNoDevEndpoints(context, http);
        }
    }
}
