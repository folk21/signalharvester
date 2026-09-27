CREATE TABLE operations.automatic_investigation_triggers (
    trigger_id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES operations.health_snapshots(snapshot_id) ON DELETE CASCADE,
    trigger_type VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    assessment_id UUID REFERENCES operations.incident_assessments(assessment_id) ON DELETE SET NULL,
    last_error TEXT,
    CONSTRAINT automatic_investigation_trigger_snapshot_unique UNIQUE (snapshot_id),
    CONSTRAINT automatic_investigation_trigger_state_check
        CHECK (state IN ('PENDING', 'CLAIMED', 'SUCCEEDED', 'EXHAUSTED')),
    CONSTRAINT automatic_investigation_trigger_type_check
        CHECK (trigger_type IN ('EVENT', 'PERIODIC'))
);

CREATE INDEX automatic_investigation_triggers_due_idx
    ON operations.automatic_investigation_triggers (next_attempt_at, created_at, trigger_id)
    WHERE state IN ('PENDING', 'CLAIMED');

CREATE INDEX automatic_investigation_triggers_created_idx
    ON operations.automatic_investigation_triggers (created_at DESC, trigger_id DESC);
