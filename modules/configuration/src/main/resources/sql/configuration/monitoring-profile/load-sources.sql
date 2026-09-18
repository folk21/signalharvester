SELECT source_id
  FROM configuration.monitoring_profile_sources
 WHERE profile_id = :profileId
 ORDER BY source_ordinal
