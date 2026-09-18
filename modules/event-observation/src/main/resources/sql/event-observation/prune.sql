DELETE FROM event_observation.observed_events
 WHERE observed_at < :cutoff
    OR observation_id NOT IN (
        SELECT observation_id
          FROM event_observation.observed_events
         WHERE observed_at >= :cutoff
         ORDER BY observation_id DESC
         LIMIT :maxEvents
    )
