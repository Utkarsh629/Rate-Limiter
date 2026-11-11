package com.utkarsh.ratelimiter.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.utkarsh.ratelimiter.identity.Tier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEVELOPMENT ONLY: mints signed tokens for any user, tier and role, so the API can be exercised
 * without an identity provider. Never created when the {@code prod} profile is active, and only
 * when {@code ratelimiter.dev-endpoints.enabled=true}.
 */
@RestController
@Profile("!prod")
@ConditionalOnProperty(prefix = "ratelimiter.dev-endpoints", name = "enabled", havingValue = "true")
@Tag(name = "Development only")
public class DemoTokenController {

    private final JwtEncoder encoder;
    private final JwtProperties jwt;
    private final DevEndpointsProperties dev;

    public DemoTokenController(JwtProperties jwt, DevEndpointsProperties dev) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(SecurityConfiguration.signingKey(jwt)));
        this.jwt = jwt;
        this.dev = dev;
    }

    public record DemoTokenRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,64}") String userId,
            @NotNull Tier tier,
            List<@Pattern(regexp = "ADMIN") String> roles,
            @Min(1) @Max(86_400) Integer ttlSeconds) {
    }

    public record DemoTokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    @PostMapping("/api/v1/auth/demo-token")
    @Operation(summary = "DEV ONLY: mint a signed JWT for testing")
    public DemoTokenResponse mint(@Valid @RequestBody DemoTokenRequest request) {
        Duration ttl = request.ttlSeconds() == null ? Duration.ofMinutes(15) : Duration.ofSeconds(request.ttlSeconds());
        if (ttl.compareTo(dev.maxTokenTtl()) > 0) {
            ttl = dev.maxTokenTtl();
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(request.userId())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(TokenClaims.TIER, request.tier().name())
                .claim(TokenClaims.ROLES, request.roles() == null ? List.of() : request.roles())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new DemoTokenResponse(token, "Bearer", ttl.toSeconds());
    }
}
