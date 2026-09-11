CREATE SCHEMA IF NOT EXISTS collection;

CREATE TABLE collection.collection_runs (
    collection_run_id UUID PRIMARY KEY,
    monitoring_profile_id TEXT NOT NULL CHECK (btrim(monitoring_profile_id) <> ''),
    information_category TEXT NOT NULL CHECK (btrim(information_category) <> ''),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED')),
    CHECK (finished_at >= started_at)
);

CREATE TABLE collection.collection_run_sources (
    collection_run_id UUID NOT NULL REFERENCES collection.collection_runs(collection_run_id) ON DELETE CASCADE,
    source_ordinal INTEGER NOT NULL CHECK (source_ordinal >= 0),
    source_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PUBLISHED', 'FETCH_FAILED', 'PUBLICATION_FAILED')),
    raw_item_id TEXT,
    event_id TEXT,
    failure_message TEXT,
    PRIMARY KEY (collection_run_id, source_ordinal)
);

CREATE INDEX idx_collection_runs_started_at
    ON collection.collection_runs (started_at DESC);
