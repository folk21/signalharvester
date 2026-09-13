CREATE SCHEMA IF NOT EXISTS results;

CREATE TABLE results.analyzed_items (
    monitoring_profile_id TEXT NOT NULL CHECK (btrim(monitoring_profile_id) <> ''),
    normalized_item_id VARCHAR(64) NOT NULL CHECK (length(normalized_item_id) = 64),
    analysis_event_id TEXT NOT NULL CHECK (btrim(analysis_event_id) <> ''),
    source_event_id TEXT NOT NULL CHECK (btrim(source_event_id) <> ''),
    raw_item_id TEXT NOT NULL CHECK (btrim(raw_item_id) <> ''),
    source_id TEXT NOT NULL CHECK (btrim(source_id) <> ''),
    information_category TEXT NOT NULL CHECK (btrim(information_category) <> ''),
    external_id TEXT,
    title TEXT,
    url TEXT NOT NULL CHECK (btrim(url) <> ''),
    normalized_content TEXT NOT NULL,
    content_type TEXT NOT NULL CHECK (btrim(content_type) <> ''),
    relevant BOOLEAN NOT NULL,
    classification TEXT NOT NULL CHECK (btrim(classification) <> ''),
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    explanation TEXT NOT NULL CHECK (btrim(explanation) <> ''),
    analyzer TEXT NOT NULL CHECK (btrim(analyzer) <> ''),
    published_at TIMESTAMPTZ,
    analyzed_at TIMESTAMPTZ NOT NULL,
    correlation_id TEXT NOT NULL CHECK (btrim(correlation_id) <> ''),
    traceparent TEXT,
    PRIMARY KEY (monitoring_profile_id, normalized_item_id)
);

CREATE UNIQUE INDEX uq_results_analyzed_items_analysis_event
    ON results.analyzed_items (analysis_event_id);
CREATE INDEX idx_results_analyzed_items_source
    ON results.analyzed_items (source_id);
CREATE INDEX idx_results_analyzed_items_analyzed_at
    ON results.analyzed_items (analyzed_at DESC);

CREATE TABLE results.analyzed_item_attributes (
    monitoring_profile_id TEXT NOT NULL,
    normalized_item_id VARCHAR(64) NOT NULL,
    attribute_key TEXT NOT NULL CHECK (btrim(attribute_key) <> ''),
    attribute_value TEXT NOT NULL,
    PRIMARY KEY (monitoring_profile_id, normalized_item_id, attribute_key),
    FOREIGN KEY (monitoring_profile_id, normalized_item_id)
        REFERENCES results.analyzed_items (monitoring_profile_id, normalized_item_id)
        ON DELETE CASCADE
);

CREATE TABLE results.analyzed_item_tags (
    monitoring_profile_id TEXT NOT NULL,
    normalized_item_id VARCHAR(64) NOT NULL,
    tag_ordinal INTEGER NOT NULL CHECK (tag_ordinal >= 0),
    tag TEXT NOT NULL CHECK (btrim(tag) <> ''),
    PRIMARY KEY (monitoring_profile_id, normalized_item_id, tag_ordinal),
    FOREIGN KEY (monitoring_profile_id, normalized_item_id)
        REFERENCES results.analyzed_items (monitoring_profile_id, normalized_item_id)
        ON DELETE CASCADE
);

CREATE TABLE results.rejected_items (
    source_event_id TEXT PRIMARY KEY CHECK (btrim(source_event_id) <> ''),
    analysis_event_id TEXT NOT NULL CHECK (btrim(analysis_event_id) <> ''),
    raw_item_id TEXT NOT NULL CHECK (btrim(raw_item_id) <> ''),
    normalized_item_id VARCHAR(64),
    source_id TEXT NOT NULL CHECK (btrim(source_id) <> ''),
    monitoring_profile_id TEXT NOT NULL CHECK (btrim(monitoring_profile_id) <> ''),
    information_category TEXT NOT NULL CHECK (btrim(information_category) <> ''),
    reason_code TEXT NOT NULL CHECK (btrim(reason_code) <> ''),
    explanation TEXT NOT NULL CHECK (btrim(explanation) <> ''),
    rejected_at TIMESTAMPTZ NOT NULL,
    correlation_id TEXT NOT NULL CHECK (btrim(correlation_id) <> ''),
    traceparent TEXT
);

CREATE UNIQUE INDEX uq_results_rejected_items_analysis_event
    ON results.rejected_items (analysis_event_id);
CREATE INDEX idx_results_rejected_items_profile
    ON results.rejected_items (monitoring_profile_id, rejected_at DESC);
