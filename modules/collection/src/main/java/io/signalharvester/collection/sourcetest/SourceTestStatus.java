package io.signalharvester.collection.sourcetest;

/** Terminal diagnostic outcome for one source-test request. */
public enum SourceTestStatus {
    SUCCEEDED,
    FETCH_FAILED,
    EXTRACTION_FAILED
}
