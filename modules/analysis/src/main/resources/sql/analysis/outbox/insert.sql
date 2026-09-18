INSERT INTO analysis.event_outbox (
    event_id, topic, event_key, payload, traceparent, created_at, publication_attempts
) VALUES (:eventId, :topic, :eventKey, :payload, :traceparent, :createdAt, :publicationAttempts)
