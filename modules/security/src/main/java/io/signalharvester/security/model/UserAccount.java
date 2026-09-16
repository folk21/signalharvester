package io.signalharvester.security.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Persisted security identity with explicit additive roles and account state. */
public record UserAccount(
        UserId id,
        String username,
        IdentityType identityType,
        boolean enabled,
        Set<UserRole> roles,
        Instant createdAt,
        Instant updatedAt) {

    public UserAccount {
        Objects.requireNonNull(id, "id");
        username = requireNonBlank(username, "username");
        Objects.requireNonNull(identityType, "identityType");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
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
