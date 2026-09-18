SELECT COALESCE(MAX(observation_id), 0)
  FROM event_observation.observed_events
