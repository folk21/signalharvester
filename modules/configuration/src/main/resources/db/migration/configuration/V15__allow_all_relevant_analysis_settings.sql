ALTER TABLE configuration.monitoring_profiles
    DROP CONSTRAINT IF EXISTS monitoring_profiles_analysis_minimum_matches_check;

ALTER TABLE configuration.monitoring_profiles
    ADD CONSTRAINT monitoring_profiles_analysis_minimum_matches_check
        CHECK (analysis_minimum_matches IS NULL OR analysis_minimum_matches >= 0);
