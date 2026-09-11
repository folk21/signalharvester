package io.signalharvester.collection.run;

/** Signals that an operational collection-run lookup referenced an unknown run id. */
public final class CollectionRunNotFoundException extends RuntimeException {
    public CollectionRunNotFoundException(String collectionRunId) {
        super("Collection run not found: " + collectionRunId);
    }
}
