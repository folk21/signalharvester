ALTER TABLE collection.collection_run_sources
    DROP CONSTRAINT collection_run_sources_status_check;

ALTER TABLE collection.collection_run_sources
    ADD CONSTRAINT collection_run_sources_status_check
    CHECK (status IN ('PUBLISHED', 'NO_ITEMS', 'FETCH_FAILED', 'EXTRACTION_FAILED', 'PUBLICATION_FAILED'));
