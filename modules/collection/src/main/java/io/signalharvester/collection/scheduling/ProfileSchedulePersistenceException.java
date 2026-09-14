package io.signalharvester.collection.scheduling;

/** Raised when collection-owned persisted schedule state cannot be updated safely. */
public final class ProfileSchedulePersistenceException extends RuntimeException {
    public ProfileSchedulePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
