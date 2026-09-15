CREATE TABLE analysis.event_outbox (
    event_id VARCHAR(64) PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NULL,
    lease_token UUID NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    publication_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publication_attempts >= 0),
    last_error VARCHAR(1000) NULL
);

CREATE INDEX analysis_event_outbox_pending_idx
    ON analysis.event_outbox (created_at, event_id)
    WHERE published_at IS NULL;
