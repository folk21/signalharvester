package io.signalharvester.security.http;

import io.micronaut.security.authentication.Authentication;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Browser-facing representation of the currently authenticated principal. */
@Serdeable
public record CurrentPrincipalResponse(
        UUID id,
        String username,
        String identityType,
        List<String> roles) {

    static CurrentPrincipalResponse from(Authentication authentication) {
        Map<String, Object> attributes = authentication.getAttributes();
        Object username = attributes.get("username");
        Object identityType = attributes.get("identityType");
        return new CurrentPrincipalResponse(
                UUID.fromString(authentication.getName()),
                username == null ? authentication.getName() : username.toString(),
                identityType == null ? "UNKNOWN" : identityType.toString(),
                authentication.getRoles().stream().sorted().toList());
    }
}
