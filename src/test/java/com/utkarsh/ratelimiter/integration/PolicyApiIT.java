package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import com.utkarsh.ratelimiter.support.ApiTestSupport;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Policy CRUD over HTTP: contract, validation, conflicts, security, and local cache refresh. */
class PolicyApiIT extends ApiTestSupport {

    private static final String POLICIES = "/api/v1/policies";

    private static Map<String, Object> body(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "integration test policy");
        body.put("httpMethod", "GET");
        body.put("endpointPattern", "/api/v1/orders/**");
        body.put("tier", "PREMIUM");
        body.put("keyBy", "USER");
        body.put("algorithm", "TOKEN_BUCKET");
        body.put("limit", 50);
        body.put("windowMs", 60_000);
        body.put("refillTokens", 10);
        body.put("failureMode", "FAIL_CLOSED");
        body.put("priority", 5);
        return body;
    }

    private Map<String, Object> create(Map<String, Object> request) {
        ResponseEntity<Map<String, Object>> response = callForMap(HttpMethod.POST, POLICIES, tokens.admin(), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("errors");
    }

    // ---------- CRUD ----------

    @Test
    void createReturns201WithLocationAndStoredRepresentation() {
        ResponseEntity<Map<String, Object>> response = callForMap(HttpMethod.POST, POLICIES, tokens.admin(), body("it-create"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> created = response.getBody();
        assertThat(response.getHeaders().getLocation()).hasToString(POLICIES + "/" + created.get("id"));
        assertThat(created).containsEntry("name", "it-create").containsEntry("limit", 50).containsEntry("refillTokens", 10)
                .containsEntry("tier", "PREMIUM").containsEntry("failureMode", "FAIL_CLOSED").containsEntry("enabled", true)
                .containsEntry("version", 0).containsKeys("createdAt", "updatedAt");
    }

    @Test
    void getListUpdateDeleteRoundTrip() {
        Map<String, Object> created = create(body("it-roundtrip"));
        String location = POLICIES + "/" + created.get("id");

        assertThat(callForMap(HttpMethod.GET, location, tokens.admin(), null).getBody()).containsEntry("name", "it-roundtrip");
        Map<String, Object> page = callForMap(HttpMethod.GET, POLICIES + "?size=100", tokens.admin(), null).getBody();
        assertThat(page).containsKeys("content", "page", "size", "totalElements", "totalPages");
        assertThat((List<?>) page.get("content")).extracting("name").contains("it-roundtrip", "free-tier");

        Map<String, Object> update = body("it-roundtrip");
        update.put("limit", 75);
        update.put("version", 0);
        ResponseEntity<Map<String, Object>> updated = callForMap(HttpMethod.PUT, location, tokens.admin(), update);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).containsEntry("limit", 75).containsEntry("version", 1);

        assertThat(call(HttpMethod.DELETE, location, tokens.admin(), null, Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(callForMap(HttpMethod.GET, location, tokens.admin(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, location, tokens.admin(), null, Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void changesReachThisInstancesSnapshotWithoutRestart() {
        Map<String, Object> created = create(body("it-snapshot"));
        long id = ((Number) created.get("id")).longValue();
        await().atMost(Duration.ofSeconds(3)).until(() -> policyCache.snapshot().policies().stream().anyMatch(p -> p.id() == id));
        ResolvedPolicy resolved = policyCache.snapshot().policies().stream().filter(p -> p.id() == id).findFirst().orElseThrow();
        assertThat(resolved.rule().limit()).isEqualTo(50);
        assertThat(resolved.rule().refillTokens()).isEqualTo(10);

        Map<String, Object> disable = body("it-snapshot");
        disable.put("enabled", false);
        disable.put("version", 0);
        callForMap(HttpMethod.PUT, POLICIES + "/" + id, tokens.admin(), disable);
        await().atMost(Duration.ofSeconds(3)).until(() -> policyCache.snapshot().policies().stream().noneMatch(p -> p.id() == id));
    }

    // ---------- validation ----------

    @Test
    void missingAndOutOfRangeFieldsAreListed() {
        Map<String, Object> bad = body("it-bad");
        bad.remove("keyBy");
        bad.put("limit", 0);
        bad.put("windowMs", 50);

        ResponseEntity<Map<String, Object>> response = callForMap(HttpMethod.POST, POLICIES, tokens.admin(), bad);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(errors(response)).extracting("field").containsExactlyInAnyOrder("keyBy", "limit", "windowMs");
    }

    @Test
    void refillTokensOnWindowAlgorithmIsRejected() {
        Map<String, Object> bad = body("it-bad-refill");
        bad.put("algorithm", "FIXED_WINDOW");

        ResponseEntity<Map<String, Object>> response = callForMap(HttpMethod.POST, POLICIES, tokens.admin(), bad);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(response)).extracting("field").containsExactly("refillTokens");
    }

    @Test
    void unparsablePathPatternIsRejected() {
        Map<String, Object> bad = body("it-bad-pattern");
        bad.put("endpointPattern", "/api/{unclosed");

        ResponseEntity<Map<String, Object>> response = callForMap(HttpMethod.POST, POLICIES, tokens.admin(), bad);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(response)).extracting("field").containsExactly("endpointPattern");
    }

    @Test
    void regexPathVariableIsRejected() {
        Map<String, Object> bad = body("it-regex-pattern");
        bad.put("endpointPattern", "/api/v1/demo/{n:[a-z]+}");

        ResponseEntity<Map<String, Object>> response = callForMap(HttpMethod.POST, POLICIES, tokens.admin(), bad);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(response)).extracting("field").containsExactly("endpointPattern");
    }

    @Test
    void unknownEnumValueIsRejected() {
        Map<String, Object> bad = body("it-bad-enum");
        bad.put("algorithm", "LEAKY_BUCKET");
        assertThat(callForMap(HttpMethod.POST, POLICIES, tokens.admin(), bad).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void pageSizeIsBounded() {
        assertThat(callForMap(HttpMethod.GET, POLICIES + "?size=101", tokens.admin(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- conflicts ----------

    @Test
    void duplicateNameIs409() {
        create(body("it-dup"));
        ResponseEntity<Map<String, Object>> response = callForMap(HttpMethod.POST, POLICIES, tokens.admin(), body("it-dup"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("detail").toString()).contains("it-dup");
    }

    @Test
    void staleVersionIs409AndMissingVersionIs400() {
        Map<String, Object> created = create(body("it-version"));
        String location = POLICIES + "/" + created.get("id");
        Map<String, Object> update = body("it-version");
        update.put("version", 0);

        // A PUT that changes nothing is idempotent: no UPDATE is issued, so the version stays 0.
        assertThat(callForMap(HttpMethod.PUT, location, tokens.admin(), update).getBody()).containsEntry("version", 0);

        // Admin A changes the limit (version 0 -> 1).
        update.put("limit", 60);
        assertThat(callForMap(HttpMethod.PUT, location, tokens.admin(), update).getBody()).containsEntry("version", 1);

        // Admin B, still holding version 0, must not overwrite A's change.
        update.put("limit", 70);
        assertThat(callForMap(HttpMethod.PUT, location, tokens.admin(), update).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(callForMap(HttpMethod.GET, location, tokens.admin(), null).getBody()).containsEntry("limit", 60);

        update.remove("version");
        ResponseEntity<Map<String, Object>> noVersion = callForMap(HttpMethod.PUT, location, tokens.admin(), update);
        assertThat(noVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(noVersion)).extracting("field").containsExactly("version");
    }

    // ---------- security ----------

    @Test
    void policyApiRequiresAValidAdminToken() {
        assertThat(callForMap(HttpMethod.GET, POLICIES, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callForMap(HttpMethod.GET, POLICIES, tokens.user("u1", Tier.ENTERPRISE), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(callForMap(HttpMethod.GET, POLICIES, tokens.expiredAdmin(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callForMap(HttpMethod.GET, POLICIES, tokens.adminSignedWithAnotherKey(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callForMap(HttpMethod.GET, POLICIES, tokens.adminFromAnotherIssuer(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callForMap(HttpMethod.GET, POLICIES, "not.a.jwt", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callForMap(HttpMethod.GET, POLICIES, tokens.admin(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
