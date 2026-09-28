CREATE SCHEMA IF NOT EXISTS operations;

CREATE TABLE operations.change_journal (
    change_id UUID PRIMARY KEY,
    changed_at TIMESTAMPTZ NOT NULL,
    category VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(256) NOT NULL,
    before_state JSONB NOT NULL,
    after_state JSONB NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    change_source VARCHAR(32) NOT NULL,
    actor_id VARCHAR(256) NOT NULL,
    correlation_id VARCHAR(256) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    application_version VARCHAR(128) NOT NULL
);

CREATE INDEX change_journal_changed_at_idx
    ON operations.change_journal (changed_at DESC, change_id DESC);

CREATE INDEX change_journal_target_idx
    ON operations.change_journal (target_type, target_id, changed_at DESC);

CREATE TABLE operations.health_snapshots (
    snapshot_id UUID PRIMARY KEY,
    generated_at TIMESTAMPTZ NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    window_ended_at TIMESTAMPTZ NOT NULL,
    overall_status VARCHAR(32) NOT NULL,
    health_score INTEGER NOT NULL CHECK (health_score BETWEEN 0 AND 100),
    policy_version VARCHAR(128) NOT NULL,
    component_statuses JSONB NOT NULL,
    signal_values JSONB NOT NULL,
    anomaly_candidates JSONB NOT NULL,
    recent_change_ids JSONB NOT NULL,
    application_version VARCHAR(128) NOT NULL,
    evidence_complete BOOLEAN NOT NULL,
    unknown_reasons JSONB NOT NULL
);

CREATE INDEX health_snapshots_generated_at_idx
    ON operations.health_snapshots (generated_at DESC, snapshot_id DESC);
