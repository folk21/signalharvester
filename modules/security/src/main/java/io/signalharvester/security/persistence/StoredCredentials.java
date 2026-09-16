package io.signalharvester.security.persistence;

import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserId;
import io.signalharvester.security.model.UserRole;
import java.util.Objects;
import java.util.Set;

/** Internal authentication projection containing the password hash and authorization state. */
public record StoredCredentials(
        UserId userId,
        String username,
        IdentityType identityType,
        boolean enabled,
        String passwordHash,
        Set<UserRole> roles) {
    public StoredCredentials {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(identityType, "identityType");
        Objects.requireNonNull(passwordHash, "passwordHash");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
