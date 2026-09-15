package io.signalharvester.security.authentication;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.security.token.claims.ClaimsAudienceProvider;
import jakarta.inject.Singleton;
import java.util.List;

/** Supplies the stable SignalHarvester API audience claim for generated JWTs. */
@Singleton
@Requires(property = "micronaut.security.enabled", value = "true")
public final class SignalHarvesterClaimsAudienceProvider implements ClaimsAudienceProvider {
    private final List<String> audience;

    public SignalHarvesterClaimsAudienceProvider(
            @Value("${signalharvester.security.jwt.audience:signalharvester-api}") String audience) {
        this.audience = List.of(audience);
    }

    @Override
    public List<String> audience() {
        return audience;
    }
}
