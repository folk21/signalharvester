SELECT COUNT(*) AS pending_count,
       MIN(created_at) AS oldest_created_at
  FROM analysis.event_outbox
 WHERE published_at IS NULL
