SELECT keyword
  FROM configuration.monitoring_profile_analysis_keywords
 WHERE profile_id = :profileId
 ORDER BY keyword_ordinal
