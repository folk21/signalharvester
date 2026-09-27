UPDATE operations.automatic_investigation_triggers
SET state = 'SUCCEEDED',
    completed_at = :completedAt,
    assessment_id = :assessmentId,
    lease_token = NULL,
    lease_expires_at = NULL,
    last_error = NULL
WHERE trigger_id = :triggerId
  AND state = 'CLAIMED'
  AND lease_token = :leaseToken
  AND lease_expires_at > :completedAt
