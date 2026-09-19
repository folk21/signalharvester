package io.signalharvester.security.application;

import io.micronaut.core.annotation.Introspected;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Administrative command for creating one persisted security identity. */
@Introspected
public record CreateUserCommand(
        @NotBlank
        @Size(max = MAX_USERNAME_LENGTH)
        @Pattern(regexp = USERNAME_PATTERN, message = "username must not contain control characters")
        String username,
        @NotBlank String password,
        @NotNull IdentityType identityType,
        boolean enabled,
        Set<@NotNull UserRole> roles) {

    public static final int MAX_USERNAME_LENGTH = 200;
    public static final String USERNAME_PATTERN = "[^\\p{Cc}]*";

    public CreateUserCommand {
        username = username == null ? null : username.trim();
        roles = roles == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }
}
