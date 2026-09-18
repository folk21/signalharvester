SELECT criterion_key, criterion_value
  FROM configuration.monitoring_profile_criteria
 WHERE profile_id = :profileId
 ORDER BY criterion_key
