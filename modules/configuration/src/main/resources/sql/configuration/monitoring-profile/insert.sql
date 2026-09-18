INSERT INTO configuration.monitoring_profiles (
    id,
    name,
    information_category,
    enabled,
    collection_interval_minutes,
    analysis_minimum_matches
) VALUES (
    :id,
    :name,
    :informationCategory,
    :enabled,
    :collectionIntervalMinutes,
    :analysisMinimumMatches
)
