CREATE TABLE operations.incident_assessments (
    assessment_id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES operations.health_snapshots(snapshot_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(32) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    model VARCHAR(256) NOT NULL,
    summary TEXT NOT NULL,
    suspected_subsystems JSONB NOT NULL,
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence >= 0.0 AND confidence <= 1.0),
    observations JSONB NOT NULL,
    hypotheses JSONB NOT NULL,
    evidence_references JSONB NOT NULL,
    recommended_checks JSONB NOT NULL,
    human_attention_suggested BOOLEAN NOT NULL
);

CREATE INDEX incident_assessments_created_at_idx
    ON operations.incident_assessments (created_at DESC, assessment_id DESC);

CREATE INDEX incident_assessments_snapshot_idx
    ON operations.incident_assessments (snapshot_id, created_at DESC);
