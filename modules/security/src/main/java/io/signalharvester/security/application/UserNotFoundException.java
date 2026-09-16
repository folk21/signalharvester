package io.signalharvester.security.application;

import io.signalharvester.security.model.UserId;

/** Signals that an administrative user operation referenced an unknown identity. */
public final class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UserId userId) {
        super("User not found: " + userId.value());
    }
}
