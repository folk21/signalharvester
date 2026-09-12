package io.signalharvester.collection.api;

/**
 * Describes the aggregate outcome of an explicit collection run.
 */
public enum CollectionRunStatus {
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED
}
