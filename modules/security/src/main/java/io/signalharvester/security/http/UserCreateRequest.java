package io.signalharvester.security.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.security.application.CreateUserCommand;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** REST payload for creating one human or system identity. */
@Serdeable
public record UserCreateRequest(
        @NotBlank
        @Size(max = CreateUserCommand.MAX_USERNAME_LENGTH)
        @Pattern(regexp = CreateUserCommand.USERNAME_PATTERN, message = "username must not contain control characters")
        String username,
        @NotBlank @Size(max = 1024) String password,
        @NotNull IdentityType identityType,
        boolean enabled,
        Set<@NotNull UserRole> roles) {

    CreateUserCommand toCommand() {
        return new CreateUserCommand(username, password, identityType, enabled, roles == null ? Set.of() : roles);
    }
}
