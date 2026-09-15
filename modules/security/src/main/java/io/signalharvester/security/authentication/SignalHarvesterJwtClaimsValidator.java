package io.signalharvester.security.authentication;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import io.signalharvester.security.model.UserRole;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.UUID;

/** Validates SignalHarvester-specific stable principal and role claims on every accepted JWT. */
@Singleton
@Requires(property = "micronaut.security.enabled", value = "true")
public final class SignalHarvesterJwtClaimsValidator implements GenericJwtClaimsValidator<HttpRequest<?>> {

    @Override
    public boolean validate(Claims claims, HttpRequest<?> request) {
        if (!hasStableSubject(claims.get("sub"))) {
            return false;
        }
        Object rolesClaim = claims.get("roles");
        if (!(rolesClaim instanceof Collection<?> roles) || roles.isEmpty()) {
            return false;
        }
        for (Object role : roles) {
            if (!(role instanceof String roleName) || !isKnownRole(roleName)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasStableSubject(Object subject) {
        if (!(subject instanceof String value)) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isKnownRole(String roleName) {
        try {
            UserRole.valueOf(roleName);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
