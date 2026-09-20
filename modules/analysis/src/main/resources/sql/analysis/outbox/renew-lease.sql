UPDATE analysis.event_outbox
   SET lease_expires_at = :leaseExpiresAt
 WHERE event_id = :eventId
   AND lease_token = :leaseToken
   AND published_at IS NULL
