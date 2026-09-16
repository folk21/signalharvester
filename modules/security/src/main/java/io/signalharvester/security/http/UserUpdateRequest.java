package io.signalharvester.security.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.security.application.UpdateUserCommand;
import io.signalharvester.security.model.UserRole;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/** REST payload for replacing an identity's enabled state and role assignments. */
@Serdeable
public record UserUpdateRequest(boolean enabled, @NotNull Set<@NotNull UserRole> roles) {
    UpdateUserCommand toCommand() {
        return new UpdateUserCommand(enabled, roles);
    }
}
