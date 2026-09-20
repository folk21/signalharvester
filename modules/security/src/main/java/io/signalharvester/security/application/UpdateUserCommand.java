package io.signalharvester.security.application;

import io.micronaut.core.annotation.Introspected;
import io.signalharvester.security.model.UserRole;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Administrative command for changing account state and explicit role assignments. */
@Introspected
public record UpdateUserCommand(
        boolean enabled,
        @NotNull Set<@NotNull UserRole> roles) {

    public UpdateUserCommand {
        roles = roles == null
                ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }
}
