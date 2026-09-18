UPDATE analysis.event_outbox
   SET published_at = :publishedAt,
       lease_token = NULL,
       lease_expires_at = NULL,
       last_error = NULL
 WHERE event_id = :eventId
   AND lease_token = :leaseToken
   AND published_at IS NULL
