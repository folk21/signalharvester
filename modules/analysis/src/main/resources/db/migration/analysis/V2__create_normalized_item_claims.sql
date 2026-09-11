CREATE SCHEMA IF NOT EXISTS analysis;

CREATE TABLE analysis.normalized_item_claims (
    monitoring_profile_id TEXT NOT NULL CHECK (btrim(monitoring_profile_id) <> ''),
    normalized_item_id VARCHAR(64) NOT NULL CHECK (length(normalized_item_id) = 64),
    source_id TEXT NOT NULL CHECK (btrim(source_id) <> ''),
    external_id TEXT,
    source_url TEXT NOT NULL CHECK (btrim(source_url) <> ''),
    first_raw_item_id TEXT NOT NULL CHECK (btrim(first_raw_item_id) <> ''),
    first_source_event_id TEXT NOT NULL CHECK (btrim(first_source_event_id) <> ''),
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_raw_item_id TEXT NOT NULL CHECK (btrim(last_raw_item_id) <> ''),
    last_source_event_id TEXT NOT NULL CHECK (btrim(last_source_event_id) <> ''),
    last_seen_at TIMESTAMPTZ NOT NULL,
    discovery_count BIGINT NOT NULL CHECK (discovery_count >= 1),
    PRIMARY KEY (monitoring_profile_id, normalized_item_id)
);

CREATE INDEX idx_analysis_normalized_item_claims_source
    ON analysis.normalized_item_claims (source_id);
