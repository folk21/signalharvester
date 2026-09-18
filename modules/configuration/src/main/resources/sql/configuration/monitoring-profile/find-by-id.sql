SELECT id,
       name,
       information_category,
       enabled,
       collection_interval_minutes,
       analysis_minimum_matches
  FROM configuration.monitoring_profiles
 WHERE id = :profileId
