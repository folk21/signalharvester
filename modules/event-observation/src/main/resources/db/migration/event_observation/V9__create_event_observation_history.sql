CREATE SCHEMA IF NOT EXISTS event_observation;

CREATE TABLE event_observation.observed_events (
    observation_id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL UNIQUE,
    event_type VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    traceparent VARCHAR(255),
    producer VARCHAR(128) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    kafka_topic VARCHAR(255) NOT NULL,
    kafka_partition INTEGER NOT NULL,
    kafka_offset BIGINT NOT NULL,
    kafka_key VARCHAR(512) NOT NULL,
    payload_type VARCHAR(128) NOT NULL,
    source_event_id VARCHAR(128),
    raw_item_id VARCHAR(128),
    normalized_item_id VARCHAR(128),
    source_id VARCHAR(128),
    monitoring_profile_id VARCHAR(128),
    information_category VARCHAR(128),
    external_id TEXT,
    title TEXT,
    url TEXT,
    content_type VARCHAR(255),
    relevant BOOLEAN,
    classification VARCHAR(255),
    score INTEGER,
    analyzer VARCHAR(255),
    reason_code VARCHAR(255),
    explanation TEXT
);

CREATE INDEX observed_events_occurred_idx
    ON event_observation.observed_events (occurred_at DESC, observation_id DESC);
CREATE INDEX observed_events_correlation_idx
    ON event_observation.observed_events (correlation_id, observation_id);
CREATE INDEX observed_events_raw_item_idx
    ON event_observation.observed_events (raw_item_id, observation_id)
    WHERE raw_item_id IS NOT NULL;
CREATE INDEX observed_events_normalized_item_idx
    ON event_observation.observed_events (normalized_item_id, observation_id)
    WHERE normalized_item_id IS NOT NULL;
CREATE INDEX observed_events_event_type_idx
    ON event_observation.observed_events (event_type, observation_id);
CREATE INDEX observed_events_topic_idx
    ON event_observation.observed_events (kafka_topic, observation_id);
