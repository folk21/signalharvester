package io.signalharvester.collection.persistence;

/** Wraps collection-owned operational history persistence failures. */
public final class CollectionRunPersistenceException extends RuntimeException {
    public CollectionRunPersistenceException(String message) {
        super(message);
    }

    public CollectionRunPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
