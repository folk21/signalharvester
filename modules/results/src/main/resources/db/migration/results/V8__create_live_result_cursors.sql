CREATE SEQUENCE results.live_result_event_id_seq AS BIGINT START WITH 1;

CREATE TABLE results.live_result_cursors (
    monitoring_profile_id TEXT NOT NULL CHECK (btrim(monitoring_profile_id) <> ''),
    normalized_item_id VARCHAR(64) NOT NULL CHECK (length(normalized_item_id) = 64),
    analysis_event_id TEXT NOT NULL CHECK (btrim(analysis_event_id) <> ''),
    live_event_id BIGINT NOT NULL,
    PRIMARY KEY (monitoring_profile_id, normalized_item_id),
    UNIQUE (live_event_id),
    FOREIGN KEY (monitoring_profile_id, normalized_item_id)
        REFERENCES results.analyzed_items (monitoring_profile_id, normalized_item_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_results_live_result_cursors_event_id
    ON results.live_result_cursors (live_event_id);

INSERT INTO results.live_result_cursors (
    monitoring_profile_id,
    normalized_item_id,
    analysis_event_id,
    live_event_id
)
SELECT monitoring_profile_id,
       normalized_item_id,
       analysis_event_id,
       nextval('results.live_result_event_id_seq')
  FROM results.analyzed_items
 ORDER BY analyzed_at, monitoring_profile_id, normalized_item_id;
