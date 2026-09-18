UPDATE configuration.monitoring_profiles
   SET name = :name,
       information_category = :informationCategory,
       enabled = :enabled,
       collection_interval_minutes = :collectionIntervalMinutes,
       analysis_minimum_matches = :analysisMinimumMatches
 WHERE id = :id
