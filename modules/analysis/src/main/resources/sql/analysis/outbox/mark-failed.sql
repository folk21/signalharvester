UPDATE analysis.event_outbox
   SET lease_token = NULL,
       lease_expires_at = :nextAttemptAt,
       last_error = :failureMessage
 WHERE event_id = :eventId
   AND lease_token = :leaseToken
   AND published_at IS NULL
