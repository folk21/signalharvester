ALTER TABLE operations.health_snapshots
    ADD COLUMN anomaly_details JSONB NOT NULL DEFAULT '[]'::jsonb;
