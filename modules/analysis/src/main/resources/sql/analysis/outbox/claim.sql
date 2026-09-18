WITH candidates AS (
    SELECT event_id
      FROM analysis.event_outbox
     WHERE published_at IS NULL
       AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
     ORDER BY created_at, event_id
     FOR UPDATE SKIP LOCKED
     LIMIT :limit
)
UPDATE analysis.event_outbox outbox
   SET lease_token = :leaseToken,
       lease_expires_at = :leaseExpiresAt,
       publication_attempts = publication_attempts + 1
  FROM candidates
 WHERE outbox.event_id = candidates.event_id
RETURNING outbox.event_id,
          outbox.topic,
          outbox.event_key,
          outbox.payload,
          outbox.traceparent,
          outbox.created_at,
          outbox.publication_attempts
