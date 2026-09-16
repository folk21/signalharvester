package io.signalharvester.security.application;

import io.signalharvester.security.model.UserId;

/** Rejects an administrative update that would leave the deployment without an enabled ADMIN identity. */
public final class LastEnabledAdministratorException extends RuntimeException {
    public LastEnabledAdministratorException(UserId userId) {
        super("Cannot remove enabled ADMIN capability from the last enabled administrator " + userId.value());
    }
}
