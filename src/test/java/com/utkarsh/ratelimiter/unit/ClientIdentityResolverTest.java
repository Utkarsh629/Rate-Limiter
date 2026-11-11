package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.ClientIdentityResolver;
import com.utkarsh.ratelimiter.identity.Tier;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ClientIdentityResolverTest {

    private final ClientIdentityResolver resolver = new ClientIdentityResolver();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String remoteAddr, String xForwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xForwardedFor != null) {
            request.addHeader("X-Forwarded-For", xForwardedFor);
        }
        return request;
    }

    private static void authenticate(Map<String, Object> claims) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "HS256").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(jwt::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    void anonymousCallerIsIdentifiedByRemoteAddress() {
        assertThat(resolver.resolve(request("198.51.100.4", null))).isEqualTo(ClientIdentity.anonymous("198.51.100.4"));
    }

    @Test
    void springAnonymousTokenIsStillAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(resolver.resolve(request("198.51.100.4", null)).authenticated()).isFalse();
    }

    @Test
    void authenticatedCallerIsIdentifiedBySubjectAndTier() {
        authenticate(Map.of("sub", "u-42", "tier", "PREMIUM"));
        assertThat(resolver.resolve(request("198.51.100.4", null))).isEqualTo(new ClientIdentity("u-42", Tier.PREMIUM, "198.51.100.4"));
    }

    @ParameterizedTest(name = "tier claim {0} -> {1}")
    @CsvSource({"FREE, FREE", "ENTERPRISE, ENTERPRISE", "ANONYMOUS, FREE", "GOLD, FREE", "premium, FREE"})
    void tierClaimNeverGrantsMoreThanItSays(String claim, Tier expected) {
        authenticate(Map.of("sub", "u", "tier", claim));
        assertThat(resolver.resolve(request("1.2.3.4", null)).tier()).isEqualTo(expected);
    }

    @Test
    void missingTierClaimMeansFree() {
        authenticate(Map.of("sub", "u"));
        assertThat(resolver.resolve(request("1.2.3.4", null)).tier()).isEqualTo(Tier.FREE);
    }

    @Test
    void xForwardedForHeaderIsNeverReadDirectly() {
        // Proxy handling is Tomcat's RemoteIpValve's job (only for trusted peers); see TrustedProxyIT.
        assertThat(resolver.resolve(request("198.51.100.4", "6.6.6.6")).clientIp()).isEqualTo("198.51.100.4");
    }
}
