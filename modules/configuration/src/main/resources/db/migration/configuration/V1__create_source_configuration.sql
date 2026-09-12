CREATE SCHEMA IF NOT EXISTS configuration;

CREATE TABLE configuration.sources (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('REST', 'RSS', 'HTML')),
    location TEXT NOT NULL,
    enabled BOOLEAN NOT NULL
);

CREATE TABLE configuration.source_settings (
    source_id UUID NOT NULL REFERENCES configuration.sources(id) ON DELETE CASCADE,
    setting_key TEXT NOT NULL,
    setting_value TEXT NOT NULL,
    PRIMARY KEY (source_id, setting_key)
);

CREATE INDEX idx_configuration_sources_enabled
    ON configuration.sources (enabled)
    WHERE enabled = TRUE;
