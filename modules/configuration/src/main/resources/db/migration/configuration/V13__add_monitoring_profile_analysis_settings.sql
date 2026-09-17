ALTER TABLE configuration.monitoring_profiles
    ADD COLUMN analysis_minimum_matches INTEGER
        CHECK (analysis_minimum_matches IS NULL OR analysis_minimum_matches > 0);

CREATE TABLE configuration.monitoring_profile_analysis_keywords (
    profile_id UUID NOT NULL REFERENCES configuration.monitoring_profiles(id) ON DELETE CASCADE,
    keyword_ordinal INTEGER NOT NULL CHECK (keyword_ordinal >= 0),
    keyword TEXT NOT NULL CHECK (btrim(keyword) <> ''),
    PRIMARY KEY (profile_id, keyword_ordinal),
    UNIQUE (profile_id, keyword)
);
