CREATE TABLE configuration.monitoring_profiles (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    information_category TEXT NOT NULL CHECK (btrim(information_category) <> ''),
    enabled BOOLEAN NOT NULL,
    collection_interval_minutes INTEGER NOT NULL CHECK (collection_interval_minutes > 0)
);

CREATE TABLE configuration.monitoring_profile_sources (
    profile_id UUID NOT NULL REFERENCES configuration.monitoring_profiles(id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES configuration.sources(id),
    source_ordinal INTEGER NOT NULL CHECK (source_ordinal >= 0),
    PRIMARY KEY (profile_id, source_id),
    UNIQUE (profile_id, source_ordinal)
);

CREATE TABLE configuration.monitoring_profile_criteria (
    profile_id UUID NOT NULL REFERENCES configuration.monitoring_profiles(id) ON DELETE CASCADE,
    criterion_key TEXT NOT NULL,
    criterion_value TEXT NOT NULL,
    PRIMARY KEY (profile_id, criterion_key)
);

CREATE INDEX idx_configuration_monitoring_profiles_enabled
    ON configuration.monitoring_profiles (enabled)
    WHERE enabled = TRUE;
