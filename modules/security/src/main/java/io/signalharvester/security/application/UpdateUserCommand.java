package io.signalharvester.security.application;

import io.signalharvester.security.model.UserRole;
import java.util.Objects;
import java.util.Set;

/** Administrative command for changing account state and explicit role assignments. */
public record UpdateUserCommand(boolean enabled, Set<UserRole> roles) {
    public UpdateUserCommand {
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
