INSERT INTO collection.collection_run_sources (
    collection_run_id, source_ordinal, source_id, status,
    raw_item_id, event_id, failure_message
) VALUES (:collectionRunId, :sourceOrdinal, :sourceId, :status, :rawItemId, :eventId, :failureMessage)
