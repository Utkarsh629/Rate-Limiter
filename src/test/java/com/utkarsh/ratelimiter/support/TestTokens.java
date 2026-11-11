package com.utkarsh.ratelimiter.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.security.JwtProperties;
import com.utkarsh.ratelimiter.security.SecurityConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Mints JWTs for tests: valid ones with the application's test key, and deliberately bad ones. */
public final class TestTokens {

    private final SecretKey key;
    private final String issuer;

    public TestTokens(JwtProperties properties) {
        this.key = SecurityConfiguration.signingKey(properties);
        this.issuer = properties.issuer();
    }

    public String user(String userId, Tier tier) {
        return sign(key, claims(userId, issuer, Instant.now().plusSeconds(600)).claim("tier", tier.name()).build());
    }

    public String admin() {
        return sign(key, claims("admin-1", issuer, Instant.now().plusSeconds(600))
                .claim("tier", "ENTERPRISE").claim("roles", List.of("ADMIN")).build());
    }

    public String withClaims(String userId, Map<String, Object> extra) {
        JwtClaimsSet.Builder builder = claims(userId, issuer, Instant.now().plusSeconds(600));
        extra.forEach(builder::claim);
        return sign(key, builder.build());
    }

    /** Expired 5 minutes ago (beyond the 30 s clock skew). */
    public String expiredAdmin() {
        return sign(key, claims("admin-1", issuer, Instant.now().minusSeconds(300))
                .issuedAt(Instant.now().minusSeconds(900)).claim("roles", List.of("ADMIN")).build());
    }

    public String adminSignedWithAnotherKey() {
        SecretKey other = new SecretKeySpec(new byte[48], "HmacSHA256");
        return sign(other, claims("admin-1", issuer, Instant.now().plusSeconds(600)).claim("roles", List.of("ADMIN")).build());
    }

    public String adminFromAnotherIssuer() {
        return sign(key, claims("admin-1", "someone-else", Instant.now().plusSeconds(600)).claim("roles", List.of("ADMIN")).build());
    }

    private static JwtClaimsSet.Builder claims(String subject, String issuer, Instant expiresAt) {
        return JwtClaimsSet.builder().subject(subject).issuer(issuer).issuedAt(expiresAt.minusSeconds(600)).expiresAt(expiresAt);
    }

    private static String sign(SecretKey key, JwtClaimsSet claims) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
