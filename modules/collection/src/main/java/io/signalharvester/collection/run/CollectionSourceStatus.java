package io.signalharvester.collection.run;

/**
 * Describes the terminal outcome for one source requested by a collection run.
 */
public enum CollectionSourceStatus {
    PUBLISHED,
    FETCH_FAILED,
    PUBLICATION_FAILED
}
