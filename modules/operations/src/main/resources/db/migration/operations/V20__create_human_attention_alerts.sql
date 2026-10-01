CREATE TABLE operations.human_attention_alerts (
    alert_id UUID PRIMARY KEY,
    state VARCHAR(16) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    first_snapshot_id UUID NOT NULL,
    latest_snapshot_id UUID NOT NULL,
    latest_health_status VARCHAR(32) NOT NULL,
    latest_health_score INTEGER NOT NULL CHECK (latest_health_score BETWEEN 0 AND 100),
    latest_assessment_id UUID REFERENCES operations.incident_assessments(assessment_id) ON DELETE SET NULL,
    model_attention_suggested BOOLEAN,
    CONSTRAINT human_attention_alert_state_check CHECK (state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT human_attention_alert_severity_check CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT human_attention_alert_reason_check CHECK (reason IN ('SUSTAINED_DEGRADED', 'UNHEALTHY')),
    CONSTRAINT human_attention_alert_resolution_check CHECK (
        (state = 'OPEN' AND resolved_at IS NULL) OR
        (state = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX human_attention_alert_single_open_idx
    ON operations.human_attention_alerts ((1))
    WHERE state = 'OPEN';

CREATE INDEX human_attention_alerts_opened_idx
    ON operations.human_attention_alerts (opened_at DESC, alert_id DESC);
