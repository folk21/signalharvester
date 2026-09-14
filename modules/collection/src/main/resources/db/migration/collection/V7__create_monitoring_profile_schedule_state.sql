CREATE TABLE collection.monitoring_profile_schedule_state (
    monitoring_profile_id UUID PRIMARY KEY,
    interval_minutes INTEGER NOT NULL CHECK (interval_minutes > 0),
    next_due_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);

CREATE INDEX idx_collection_monitoring_profile_schedule_due
    ON collection.monitoring_profile_schedule_state (next_due_at)
    WHERE lease_token IS NULL;
