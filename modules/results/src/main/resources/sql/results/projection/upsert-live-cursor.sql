INSERT INTO results.live_result_cursors AS existing (
    monitoring_profile_id,
    normalized_item_id,
    analysis_event_id,
    live_event_id
) VALUES (
    :monitoringProfileId,
    :normalizedItemId,
    :analysisEventId,
    nextval('results.live_result_event_id_seq')
)
ON CONFLICT (monitoring_profile_id, normalized_item_id) DO UPDATE SET
    analysis_event_id = EXCLUDED.analysis_event_id,
    live_event_id = CASE
        WHEN existing.analysis_event_id = EXCLUDED.analysis_event_id
            THEN existing.live_event_id
        ELSE EXCLUDED.live_event_id
    END
