package com.utkarsh.ratelimiter.unit;

import static com.utkarsh.ratelimiter.unit.PolicyFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.PolicySnapshot;
import com.utkarsh.ratelimiter.policy.ResolvedPolicy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

/** Policy resolution precedence, as a truth table. */
class PolicySnapshotTest {

    // 1: any tier, /api/**          2: FREE, /api/**                3: any tier, /api/v1/orders/**
    // 4: any tier, POST /api/v1/orders/**                           5: FREE, /api/v1/orders/{id}
    // 6: any tier, /api/v1/orders/** priority 10 (beats 3 on priority only)
    private final PolicySnapshot snapshot = PolicySnapshot.of(List.of(
            policy(1, null, "/api/**", null, 0),
            policy(2, null, "/api/**", Tier.FREE, 0),
            policy(3, null, "/api/v1/orders/**", null, 0),
            policy(4, "POST", "/api/v1/orders/**", null, 0),
            policy(5, null, "/api/v1/orders/{id}", Tier.FREE, 0),
            policy(6, null, "/api/v1/orders/**", null, 10)), PolicySnapshot.Source.DATABASE, Instant.EPOCH, 0);

    @ParameterizedTest(name = "{0} {1} as {2} -> policy {3}")
    @CsvSource({
            // tier-specific beats any-tier, even with a less specific path
            "GET,  /api/v1/orders/7,   FREE,       5",
            "GET,  /api/v1/users,      FREE,       2",
            // no tier-specific match: most specific path, then method, then priority
            "GET,  /api/v1/orders/7,   PREMIUM,    6",
            "POST, /api/v1/orders/7,   PREMIUM,    4",
            "GET,  /api/v1/users,      ENTERPRISE, 1",
            "GET,  /api/v1/users,      ANONYMOUS,  1",
            // FREE + nested orders path: {id} matches one segment only, so falls back to FREE /api/**
            "GET,  /api/v1/orders/7/items, FREE,   2",
    })
    void mostSpecificMatchingPolicyWins(String method, String path, Tier tier, long expectedId) {
        assertThat(snapshot.resolve(HttpMethod.valueOf(method), path, tier)).map(ResolvedPolicy::id).contains(expectedId);
    }

    @Test
    void noMatchingPolicyMeansNoLimit() {
        assertThat(snapshot.resolve(HttpMethod.GET, "/actuator/health", Tier.FREE)).isEmpty();
    }

    @Test
    void lowerIdBreaksFullTies() {
        PolicySnapshot tied = PolicySnapshot.of(List.of(policy(9, null, "/a/**", null, 0), policy(8, null, "/a/**", null, 0)),
                PolicySnapshot.Source.DATABASE, Instant.EPOCH, 0);
        assertThat(tied.resolve(HttpMethod.GET, "/a/b", Tier.FREE)).map(ResolvedPolicy::id).contains(8L);
    }

    @Test
    void emptySnapshotIsUninitialised() {
        assertThat(PolicySnapshot.empty().source()).isEqualTo(PolicySnapshot.Source.NONE);
        assertThat(PolicySnapshot.empty().resolve(HttpMethod.GET, "/api/x", Tier.FREE)).isEmpty();
    }
}
