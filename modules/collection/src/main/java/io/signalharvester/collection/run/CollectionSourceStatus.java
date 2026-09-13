package io.signalharvester.collection.run;

/** Describes one terminal source/item outcome recorded for a collection run. */
public enum CollectionSourceStatus {
    PUBLISHED,
    NO_ITEMS,
    FETCH_FAILED,
    EXTRACTION_FAILED,
    PUBLICATION_FAILED
}
