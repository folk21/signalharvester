package io.signalharvester.collection.run;

import java.util.UUID;

/** Signals that an operational collection-run lookup referenced an unknown run id. */
public final class CollectionRunNotFoundException extends RuntimeException {
    public CollectionRunNotFoundException(UUID collectionRunId) {
        super("Collection run not found: " + collectionRunId);
    }
}
