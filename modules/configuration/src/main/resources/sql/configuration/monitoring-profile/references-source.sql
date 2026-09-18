SELECT 1
  FROM configuration.monitoring_profile_sources
 WHERE source_id = :sourceId
 LIMIT 1
