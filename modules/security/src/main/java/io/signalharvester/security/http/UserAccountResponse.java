package io.signalharvester.security.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.security.model.UserAccount;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST representation of one persisted security identity without credential material. */
@Serdeable
public record UserAccountResponse(
        UUID id,
        String username,
        String identityType,
        boolean enabled,
        List<String> roles,
        Instant createdAt,
        Instant updatedAt) {

    static UserAccountResponse from(UserAccount account) {
        return new UserAccountResponse(
                account.id().value(),
                account.username(),
                account.identityType().name(),
                account.enabled(),
                account.roles().stream().map(Enum::name).sorted().toList(),
                account.createdAt(),
                account.updatedAt());
    }
}
