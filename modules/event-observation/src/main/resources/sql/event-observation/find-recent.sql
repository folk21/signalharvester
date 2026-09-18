SELECT observed.*
  FROM event_observation.observed_events observed
 WHERE (:eventType IS NULL OR observed.event_type = :eventType)
   AND (:producer IS NULL OR observed.producer = :producer)
   AND (:topic IS NULL OR observed.kafka_topic = :topic)
   AND (:correlationId IS NULL OR observed.correlation_id = :correlationId)
   AND (:collectionRunId IS NULL OR observed.correlation_id = :collectionRunId)
   AND (:itemId IS NULL OR observed.raw_item_id = :itemId OR observed.normalized_item_id = :itemId)
   AND (:tracePattern IS NULL OR observed.traceparent LIKE :tracePattern)
 ORDER BY observed.occurred_at DESC, observed.observation_id DESC
 LIMIT :limit
