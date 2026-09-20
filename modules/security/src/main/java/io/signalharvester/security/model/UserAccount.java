package io.signalharvester.security.model;

import static io.signalharvester.common.validation.Preconditions.requireTrimmedNonBlank;

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
        username = requireTrimmedNonBlank(username, "username");
        Objects.requireNonNull(identityType, "identityType");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

}
