WITH candidate AS (
    SELECT trigger_id
    FROM operations.automatic_investigation_triggers
    WHERE next_attempt_at <= :now
      AND (
          state = 'PENDING'
          OR (state = 'CLAIMED' AND lease_expires_at <= :now)
      )
    ORDER BY next_attempt_at, created_at, trigger_id
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
UPDATE operations.automatic_investigation_triggers trigger
SET state = 'CLAIMED',
    lease_token = :leaseToken,
    lease_expires_at = :leaseExpiresAt,
    attempt_count = attempt_count + 1
FROM candidate
WHERE trigger.trigger_id = candidate.trigger_id
RETURNING trigger.trigger_id,
          trigger.snapshot_id,
          trigger.trigger_type,
          trigger.attempt_count,
          trigger.lease_token,
          trigger.lease_expires_at
