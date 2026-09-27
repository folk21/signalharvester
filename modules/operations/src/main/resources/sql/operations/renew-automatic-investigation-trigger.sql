UPDATE operations.automatic_investigation_triggers
SET lease_expires_at = :leaseExpiresAt
WHERE trigger_id = :triggerId
  AND state = 'CLAIMED'
  AND lease_token = :leaseToken
  AND lease_expires_at > :renewedAt
