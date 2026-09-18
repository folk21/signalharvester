SELECT source_id, status, raw_item_id, event_id, failure_message
  FROM collection.collection_run_sources
 WHERE collection_run_id = :collectionRunId
 ORDER BY source_ordinal
