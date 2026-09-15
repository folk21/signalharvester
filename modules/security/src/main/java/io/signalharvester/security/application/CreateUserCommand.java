package io.signalharvester.security.application;

import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserRole;
import java.util.Objects;
import java.util.Set;

/** Administrative command for creating one persisted security identity. */
public record CreateUserCommand(
        String username,
        String password,
        IdentityType identityType,
        boolean enabled,
        Set<UserRole> roles) {
    public CreateUserCommand {
        username = normalizeUsername(username);
        password = requirePassword(password);
        Objects.requireNonNull(identityType, "identityType");
        roles = Set.copyOf(roles == null ? Set.of() : roles);
    }

    private static String normalizeUsername(String value) {
        String normalized = requireNonBlank(value, "username");
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("username exceeds the supported length");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("username must not contain control characters");
        }
        return normalized;
    }

    private static String requirePassword(String value) {
        Objects.requireNonNull(value, "password");
        if (value.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
