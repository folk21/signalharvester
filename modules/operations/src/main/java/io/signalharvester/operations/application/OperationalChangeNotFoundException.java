package io.signalharvester.operations.application;

import java.util.UUID;

/** Indicates that an operational change identifier is not present in the journal. */
public final class OperationalChangeNotFoundException extends RuntimeException {
    public OperationalChangeNotFoundException(UUID changeId) {
        super("Operational change not found: " + changeId);
    }
}
