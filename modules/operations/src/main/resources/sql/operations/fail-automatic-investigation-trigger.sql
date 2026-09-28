UPDATE operations.automatic_investigation_triggers
SET state = 'PENDING',
    next_attempt_at = :nextAttemptAt,
    completed_at = NULL,
    lease_token = NULL,
    lease_expires_at = NULL,
    last_error = :lastError
WHERE trigger_id = :triggerId
  AND state = 'CLAIMED'
  AND lease_token = :leaseToken
  AND lease_expires_at > :failedAt
